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
import java.util.logging.Level;
import java.util.logging.Logger;

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
    //
    // We need a reference to the `Subscriber` so we can talk to it.
    private final Subscriber<? super String> sub;
    //
    // This `ConcurrentLinkedQueue` will track the `onNext` signals that will be sent to the  `Subscriber`.
    private final ConcurrentLinkedDeque<String> lines = new ConcurrentLinkedDeque<>();
    //
    // Here we track the current demand, i.e. what has been requested but not yet delivered.
    private final AtomicLong requests = new AtomicLong();
    //
    // This flag will track whether this `Subscription` is to be considered cancelled or not.
    private volatile boolean cancelled = false;
    //
    // Need to keep track of End-of-Stream
    private boolean hasNext = true;

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
    void readLines(
        AsynchronousFileChannel asyncFile,
        int bufferSize)
    {
        readLines(asyncFile, 0, 0, 0, new byte[bufferSize], new byte[MAX_LINE_SIZE], 0);
    }

    /**
     * There is recursion on `readLines()`establishing a serial order among:
     * `readLines()` -> `produceLine()` -> `emitLine()` -> `readLines()` -> and so on.
     *
     * @param asyncFile the nio associated file channel.
     * @param position current read or write position in file.
     * @param bufpos read position in buffer.
     * @param bufsize total bytes in buffer.
     * @param buffer buffer for current producing line.
     * @param auxline the transfer buffer.
     * @param linepos current position in producing line.
     */
    void readLines(
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
                // Following, it sets hasNext to false.
                close(asyncFile);
                return;
            }
            else readLines(asyncFile, next, 0, res, buffer, auxline, lastLinePos);
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


    void close(AsynchronousFileChannel asyncFile) {
        try {
            asyncFile.close();
        } catch (IOException e) {
            sub.onError(e); // Failed terminal state.
        } finally {
            hasNext = false;
            if(lines.isEmpty()) {
                cancelled = true; // We need to consider this `Subscription` as cancelled as per rule 1.6
                sub.onComplete(); // Then we signal `onComplete` as per rule 1.2 and 1.5
            }
        }
    }
    /**
     * This is called only from readLines callback and performed from a background IO thread.
     *
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
        emitLines();
    }
    /**
     * It only emits lines if subscription is not cancelled yet and there are still
     * pending requests.
     */
    private void emitLines() {
        while(!cancelled               // This makes sure that rule 1.8 is upheld, i.e. we need to stop signalling "eventually"
              && requests.get() > 0    // This makes sure that rule 1.1 is upheld (sending more than was demanded)
              && !lines.isEmpty()) {
            String line = lines.poll();
            if(line != null) {
                sub.onNext(line);
                requests.decrementAndGet();
            } else {
                terminateDueTo(new IllegalStateException("Unexpected race occur on lines offer. No other thread should concurrently should be taking lines!"));
                break;
            }
        }
    }

    @Override
    public void request(long l) {
        if(cancelled) return;
        // The following increment may race with emitLines() execution.
        doRequest(l);
        // If hasNext is still true, then the background IO threads see the previous requests
        // increment and will emit those lines.
        // If hasNext is false then background IO threads has finished emitting lines,
        // because this flag is only set on `close()` operation and only after completion
        // of `produceLine()` call which in turn has invoked `emitLine()`.
        // Moreover hasNext is volatile, thus we should see every write made before we have
        // assigned hasNext with false.
        if(!hasNext) {
            emitLines();
            if(lines.isEmpty()) {
                cancelled = true; // We need to consider this `Subscription` as cancelled as per rule 1.6
                sub.onComplete(); // Then we signal `onComplete` as per rule 1.2 and 1.5
            }
        }
    }

    @Override
    public void cancel() {
        cancelled = true;
    }

    /**
     * This method will register inbound demand from our `Subscriber` and validate it against rule 3.9 and rule 3.17
     */
    private void doRequest(final long n) {
        if (n < 1)
            terminateDueTo(new IllegalArgumentException(sub + " violated the Reactive Streams rule 3.9 by requesting a non-positive number of elements."));
        else if (requests.get() + n < 1) {
            // As governed by rule 3.17, when demand overflows `Long.MAX_VALUE` we treat the signalled demand as "effectively unbounded"
            requests.set(Long.MAX_VALUE);  // Here we protect from the overflow and treat it as "effectively unbounded"
        } else {
            requests.addAndGet(n); // Here we record the downstream demand
        }
    }

    /**
     * This is a helper method to ensure that we always `cancel` when we signal `onError` as per rule 1.6
     */
    private void terminateDueTo(final Throwable t) {
        cancelled = true; // When we signal onError, the subscription must be considered as cancelled, as per rule 1.6
        try {
            sub.onError(t); // Then we signal the error downstream, to the `Subscriber`
        } catch (final Exception t2) { // If `onError` throws an exception, this is a spec violation according to rule 1.9, and all we can do is to log it.
            Throwable ex = new IllegalStateException(sub + " violated the Reactive Streams rule 2.13 by throwing an exception from onError.", t2);
            Logger.getGlobal().log(Level.SEVERE, "Violated the Reactive Streams rule 2.13", ex);
        }
    }
}
