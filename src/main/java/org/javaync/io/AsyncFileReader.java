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

import org.javasync.util.NewlineUtils;
import org.reactivestreams.Subscriber;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.CompletableFuture.completedFuture;

/**
 * Asynchronous non-blocking read operations with a reactive based API.
 * All read operations return a CompletableFuture or a Publisher of
 * strings corresponding to file lines.
 * These operations use an underlying AsynchronousFileChannel.
 */
public class AsyncFileReader {

    private static final CharsetDecoder decoder = UTF_8.newDecoder()
                                            .onMalformedInput(CodingErrorAction.REPORT)
                                            .onUnmappableCharacter(CodingErrorAction.REPORT);
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
                .whenComplete((data, err) -> {
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

        appendBuffer(accumulator, buffer, sub, length);
        final boolean finishesWithNewline = accumulator.charAt(accumulator.length() - 1) == '\n';
        /**
         * Notifies subscriber for each line in accumulator
         */
        Optional<String> remaining = NewlineUtils
            .splitToStream(accumulator)
            .reduce((prev, curr) -> {
                sub.onNext(prev);
                return curr;
            })
            .map(last -> {
                if (finishesWithNewline)
                    sub.onNext(last);
                else
                    // This is the last sentence and has NO newline char.
                    // So we do not notify it in onNext() and we leave it
                    // on remaining for the next parseByLineToSubscriber call.
                    return last;
                return null;
            });
        /**
         * Call readLinesToSubscriber recursively for the remaining of asyncFile
         */
        if(length < buffer.capacity()) {
            /**
             * Already reaches the end of the file.
             */
            remaining.ifPresent(sub::onNext); // So notify last string
            closeAndNotifiesCompletion(asyncFile, sub);
        }
        else {
            accumulator = remaining.isPresent()
                    ? new StringBuilder(remaining.get())
                    : new StringBuilder();
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

    private static void appendBuffer(StringBuilder accumulator, ByteBuffer buffer, Subscriber<? super String> sub, int length) {
        buffer.rewind(); // set position = 0
        try {
            accumulator.append(
                    decoder
                            .decode(buffer)
                            .limit(length)
            );
        } catch (CharacterCodingException e) {
            sub.onError(e);
        }
    }
}
