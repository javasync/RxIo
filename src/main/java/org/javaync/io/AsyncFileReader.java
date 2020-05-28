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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import java.security.InvalidParameterException;
import java.util.concurrent.CompletableFuture;
import java.util.function.ObjIntConsumer;

import static java.util.concurrent.CompletableFuture.completedFuture;

/**
 * Asynchronous non-blocking read operations with a reactive based API.
 * All read operations return a CompletableFuture or a Publisher of
 * strings corresponding to file lines.
 * These operations use an underlying AsynchronousFileChannel.
 */
public class AsyncFileReader {
    static final int BUFFER_SIZE= 4096*8;
    private static final int MAX_LINE_SIZE = 4096;
    private static final int LF = '\n';
    private static final int CR = '\r';

    private AsyncFileReader() {
    }

    /**
     * Read all bytes from an {@code AsynchronousFileChannel}, which are decoded into characters
     * using the UTF-8 charset.
     * The resulting characters are parsed by line and passed to the {@code Subscriber sub}.
     * @param asyncFile the nio associated file channel.
     * @param bufferSize
     * @param sub subscriber for resulting publisher.
     * @param sign the subscription.
     */
    static void readLinesToSubscriber(
        AsynchronousFileChannel asyncFile,
        int bufferSize,
        Subscriber<? super String> sub,
        ReaderSubscription sign)
    {
        readLinesToSubscriber(asyncFile, 0, 0, 0, new byte[bufferSize], new byte[MAX_LINE_SIZE], 0, sub, sign);
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
     * @param sub subscriber for resulting publisher.
     * @param sign subscription.
     */
    static void readLinesToSubscriber(
            AsynchronousFileChannel asyncFile,
            long position,
            int bufpos,
            int bufsize,
            byte[] buffer,
            byte[] auxline,
            int linepos,
            Subscriber<? super String> sub,
            ReaderSubscription sign)
    {
        while(bufpos < bufsize) {
            if (buffer[bufpos] == LF) {
                if (linepos > 0 && auxline[linepos-1] == CR) linepos--;
                bufpos++;
                sub.onNext(produceLine(auxline, linepos));
                linepos = 0;
            }
            else if (linepos == MAX_LINE_SIZE -1) {
                sub.onNext(produceLine(auxline, linepos));
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
                   sub.onNext(produceLine(auxline, lastLinePos));
                }
                closeAndNotifiesCompletion(asyncFile, sub);
                return;
            }
            readLinesToSubscriber(asyncFile, next, 0, res, buffer, auxline, lastLinePos, sub, sign);
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

    static CompletableFuture<Integer> readAllBytes(
            AsynchronousFileChannel asyncFile,
            ByteBuffer buffer,
            int position,
            ByteArrayOutputStream out)
    {
        return  readToByteArrayStream(asyncFile, buffer, position, out)
                        .thenCompose(index ->
                                index < 0
                                ? completedFuture(position)
                                : readAllBytes(asyncFile, buffer.clear(), position + index, out));

    }

    static CompletableFuture<Integer> readToByteArrayStream(
            AsynchronousFileChannel asyncFile,
            ByteBuffer buffer,
            int position,
            ByteArrayOutputStream out)
    {
        CompletableFuture<Integer> promise = new CompletableFuture<>();
        asyncFile.read(buffer, position, buffer, new CompletionHandler<Integer, ByteBuffer>() {
            @Override
            public void completed(Integer result, ByteBuffer attachment) {
                if(result > 0) {
                    attachment.flip();
                    byte[] data = new byte[attachment.limit()]; // limit = result
                    attachment.get(data);
                    write(out, data);
                }
                promise.complete(result);
            }

            @Override
            public void failed(Throwable exc, ByteBuffer attachment) {
                promise.completeExceptionally(exc);
            }
        });
        return promise;
    }

    static void closeAndNotifiesCompletion(AsynchronousFileChannel asyncFile, Subscriber<? super String> sub) {
        try {
            asyncFile.close();
            sub.onComplete(); // Successful terminal state.
        } catch (IOException e) {
            sub.onError(e); // Failed terminal state.
        }
    }

    static void write(ByteArrayOutputStream out, byte[] data) {
        try {
            out.write(data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * @param auxline the transfer buffer.
     * @param linepos current position in producing line.
     */
    private static String produceLine(byte[] auxline, int linepos) {
        return new String(auxline, 0, linepos, StandardCharsets.UTF_8);
    }
}
