/*
 * MIT License
 *
 * Copyright (c) 2018, Miguel Gamboa (gamboa.pt)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */

package org.javaync.io;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import java.security.InvalidParameterException;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ObjIntConsumer;

/**
 * Asynchronous non-blocking read operations with a reactive based API.
 * All read operations return a CompletableFuture or a Publisher of
 * strings corresponding to file lines.
 * These operations use an underlying AsynchronousFileChannel.
 */
public class AsyncFileReaderLines implements Subscription {
    static final int BUFFER_SIZE= 4096*8;
    private static final int MAX_LINE_SIZE = 4096;
    private static final int LF = '\n';
    private static final int CR = '\r';

    private final Subscriber<? super String> sub;
    private final ConcurrentLinkedDeque<String> lines = new ConcurrentLinkedDeque<>();
    private final AtomicLong requests = new AtomicLong();
    private volatile boolean finished = false;
    private Throwable error = null;

    AsyncFileReaderLines(Subscriber<? super String> sub) {
        this.sub = sub;
    }

    /**
     * Read all bytes from an {@code AsynchronousFileChannel}, which are decoded into characters
     * using the UTF-8 charset.
     * The resulting characters are parsed by line and passed to the {@code Subscriber sub}.
     * @param asyncFile the nio associated file channel.
     * @param bufferSize
     */
    void readLinesToSubscriber(
        AsynchronousFileChannel asyncFile,
        int bufferSize)
    {
        readLinesToSubscriber(asyncFile, 0, 0, 0, new byte[bufferSize], new byte[MAX_LINE_SIZE], 0);
    }

    /**
     * Read all bytes from an {@code AsynchronousFileChannel}, which are decoded into characters
     * using the UTF-8 charset.
     * The resulting characters are parsed by line and passed to the {@code Subscriber sub}.
     *
     * @param asyncFile the nio associated file channel.
     * @param position current read or write position in file.
     * @param bufpos read position in buffer.
     * @param bufsize total bytes in buffer.
     * @param buffer buffer for current producing line.
     * @param auxline the transfer buffer.
     * @param linepos current position in producing line.
     */
    void readLinesToSubscriber(
            AsynchronousFileChannel asyncFile,
            long position,
            int bufpos,
            int bufsize,
            byte[] buffer,
            byte[] auxline,
            int linepos)
    {
        while(bufpos < bufsize) {
            if (buffer[bufpos] == LF) {
                if (linepos > 0 && auxline[linepos-1] == CR) linepos--;
                bufpos++;
                produceLine(auxline, linepos);
                linepos = 0;
            }
            else if (linepos == MAX_LINE_SIZE -1) {
                produceLine(auxline, linepos);
                linepos = 0;
            }
            else auxline[linepos++] = buffer[bufpos++];
        }
        int lastLinePos = linepos;
        readBytes(asyncFile, position, buffer, 0, buffer.length, (err, res) -> {
            if(err != null) {
                sub.onError(err);
                return;
            }
            long next = position;
            if (res > 0) next += res;
            if (res <= 0) {
                // needed for last line that doesn't end with LF
                if (lastLinePos > 0) {
                   produceLine(auxline, lastLinePos);
                }
                closeAndNotifiesCompletion(asyncFile);
                return;
            }
            if(finished) sub.onComplete();
            else readLinesToSubscriber(asyncFile, next, 0, res, buffer, auxline, lastLinePos);
        });
    }

    /**
     * Asynchronous read chunk operation, callback based.
     */
    public static void readBytes(
        AsynchronousFileChannel asyncFile,
        long position,
        byte[] data,
        int ofs,
        int size,
        ObjIntConsumer<Throwable> completed)
    {
        if (completed == null)
            throw new InvalidParameterException("callback can't be null!");
        if (size + ofs > data.length)
            size = data.length - ofs;
        if (size ==0) {
            completed.accept(null, 0);
            return;
        }
        ByteBuffer buf = ByteBuffer.wrap(data, ofs, size);
        CompletionHandler<Integer,Object> readCompleted =
                new CompletionHandler<Integer,Object>() {
                    @Override
                    public void completed(Integer result, Object attachment) {
                        completed.accept(null, result);
                    }
                    @Override
                    public void failed(Throwable exc, Object attachment) {
                        completed.accept(exc, 0);
                    }
                };
        asyncFile.read(buf, position, null, readCompleted);
    }


    void closeAndNotifiesCompletion(AsynchronousFileChannel asyncFile) {
        try {
            asyncFile.close();
            if(lines.isEmpty()) sub.onComplete(); // Successful terminal state.
            finished = true;
        } catch (IOException e) {
            if(lines.isEmpty()) sub.onError(e); // Failed terminal state.
            error = e;
        }
    }
    /**
     * @param auxline the transfer buffer.
     * @param linepos current position in producing line.
     */
    private void produceLine(byte[] auxline, int linepos) {
        String line = new String(auxline, 0, linepos, StandardCharsets.UTF_8);
        /**
         * Always put the newly line on lines because a concurrent request
         * may be asking for new lines and we should ensure the total order.
         */
        lines.offer(line);
        emitLine();
    }
    private void emitLine() {
        if(requests.get() > 0) {
            String line = lines.poll();
            /**
             * We may have a concurrent emitLine() call resulting from the request().
             */
            if(line != null) {
                sub.onNext(line);
                requests.decrementAndGet();
            }
        }
    }

    @Override
    public void request(long l) {
        requests.addAndGet(l);
        while(requests.get() > 0 && !lines.isEmpty()) {
            emitLine();
        }
        /**
         * First empty pending lines and then complete.
         */
        if(finished) { sub.onComplete(); return; }
        if(error != null) { sub.onError(error); return; }
    }

    @Override
    public void cancel() {
        finished = true;
    }
}
