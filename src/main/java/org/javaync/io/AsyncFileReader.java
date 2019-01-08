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
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.CompletableFuture.completedFuture;

/**
 * Asynchronous non-blocking read operations with a reactive based API.
 * All read operations return a CompletableFuture or a Publisher of
 * strings corresponding to file lines.
 * These operations use an underlying AsynchronousFileChannel.
 */
public abstract class AsyncFileReader {
    /*
     * Use {@code (?<=(...))} to include the pattern characters in resulting match.
     * For instance {@code (?<=(\n))} includes the {@code \n} in resulting string line.
     */
    static final Pattern NEWLINE_INCLUSIVE = Pattern.compile("(?<=(\r\n|\n))");
    static final Pattern NEWLINE = Pattern.compile("(\r\n|\n)");

    private AsyncFileReader() {
    }

    /**
     * Read all bytes from an {@code AsynchronousFileChannel}, which are decoded into characters
     * using the UTF-8 charset.
     * The resulting characters are parsed by line and passed to the {@code Subscriber sub}.
     */
    static void readLinesToSubscriber(
            AsynchronousFileChannel asyncFile,
            int position,
            ByteBuffer buffer,
            StringBuilder res,
            Subscriber<? super String> sub)
    {
        readBytesToByteBuffer(asyncFile, buffer, position)
                .whenComplete((__, err) -> {
                    if(err != null) sub.onError(err);
                    else parseByLineToSubscriber(asyncFile, position, buffer, res, sub);
                });
    }

    /**
     * Read bytes from an {@code AsynchronousFileChannel} into the {@code ByteBuffer buffer}
     * parameter and then copy it to the {@code accumulator}.
     * For every line in the {@code accumulator} it notifies the {@code sub.onNext()}.
     * When it reaches the end of file then notifies {@code sub.onComplete()}.
     */
    static void parseByLineToSubscriber(
            AsynchronousFileChannel asyncFile,
            int position,
            ByteBuffer buffer,
            StringBuilder accumulator,
            Subscriber<? super String> sub)
    {
        final int length = buffer.position();
        if(length == 0)
            closeAndNotifiesCompletion(asyncFile, sub);

        buffer.rewind(); // set position = 0
        accumulator.append(UTF_8.decode(buffer).limit(length)); // Append buffer to StringBuidler res
        if(!NEWLINE.matcher(accumulator).find() && length == buffer.capacity()) {
            // There is NO new line in res string. Thus proceed to read next chunk of bytes.
            readLinesToSubscriber(asyncFile, position + length, buffer.clear(), accumulator, sub);
            return;
        }
        /**
         * Notifies subscriber with lines
         */
        Iterator<String> iter = NEWLINE_INCLUSIVE.splitAsStream(accumulator).iterator();
        String remaining = null;
        while(iter.hasNext()) {
            String line = iter.next();
            Matcher matcher = NEWLINE.matcher(line);
            if(!iter.hasNext() && !matcher.find()) {
                // This is the last sentence and has NO newline char.
                // So we do not want to notify it in onNext() and
                // we put it on remaining for the next iteration.
                remaining = line;
            } else {
                // Remove the newline char.
                sub.onNext(matcher.replaceFirst(""));
            }
        }
        /**
         * Call readLinesToSubscriber() recursively for the remaining of the string
         */
        if(length < buffer.capacity()) {
            /**
             * Already reaches the end of the file.
             */
            if(remaining != null)
                sub.onNext(remaining); // So notify last string
            closeAndNotifiesCompletion(asyncFile, sub);
        }
        else {
            accumulator = remaining == null
                    ? new StringBuilder()
                    : new StringBuilder(remaining);
            /**
             * Continue reading the file.
             */
            readLinesToSubscriber(asyncFile, position + length, buffer.clear(), accumulator, sub);
        }
    }

    static CompletableFuture<Void> readBytesToByteBuffer(
            AsynchronousFileChannel asyncFile,
            ByteBuffer buffer,
            int position)
    {
        CompletableFuture<Void> promise = new CompletableFuture<>();
        asyncFile.read(buffer, position, buffer, new CompletionHandler<Integer, ByteBuffer>() {
            @Override
            public void completed(Integer result, ByteBuffer attachment) {
                // Note that attachment.limit() is equal to result
                promise.complete(null);
            }

            @Override
            public void failed(Throwable exc, ByteBuffer attachment) {
                promise.completeExceptionally(exc);
            }
        });
        return promise;
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
}
