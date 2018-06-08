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

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import static java.nio.channels.AsynchronousFileChannel.open;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.CompletableFuture.completedFuture;

/**
 * Asynchronous non-blocking read operations with a reactive based API.
 * All read operations return a CompletableFuture with a single String or a
 * Publisher of strings corresponding to lines.
 * These operations use an underlying AsynchronousFileChannel.
 * All methods are asynchronous including the close() which chains a continuation
 * on last resulting read CompletableFuture to close the AsyncFileChannel on completion.
 */
public abstract class AsyncFileReader {

    static final Pattern NEWLINE = Pattern.compile("(?<=(\n))");

    /**
     * Reads the given file from the beginning using
     * an AsyncFileChannel with a ByteBuffer of
     * 1024 capacity.
     */
    public static Publisher<String> lines(String file) {
        return lines(1024, Paths.get(file));
    }

    /**
     * Reads the given file from the beginning using
     * an AsyncFileChannel with a ByteBuffer of
     * the specified bufferSize capacity.
     */
    public static Publisher<String> lines(int bufferSize, String file) {
        return lines(bufferSize, Paths.get(file));
    }

    /**
     * Reads the given file from the beginning using
     * an AsyncFileChannel with a ByteBuffer of
     * the specified bufferSize capacity.
     */
    public static Publisher<String> lines(int bufferSize, Path file) {
        return lines(bufferSize, file, StandardOpenOption.READ);
    }

    /**
     * Reads the given file from the beginning using
     * an AsyncFileChannel with a ByteBuffer of
     * the specified bufferSize capacity.
     */
    public static Publisher<String> lines(int bufferSize, Path file, StandardOpenOption...options) {
        AsynchronousFileChannel asyncFile = openFileChannel(file, options);
        ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
        return sub -> lines(asyncFile, 0, buffer, new StringBuilder(), sub);
    }

    static CompletableFuture<byte[]> lines(
            AsynchronousFileChannel asyncFile,
            int position,
            ByteBuffer buffer,
            StringBuilder res,
            Subscriber<? super String> sub)
    {
        return readBytes(asyncFile, buffer, position)
                .thenCompose(bytes -> parseByLine(asyncFile, bytes, position, buffer, res, sub));
    }

    /**
     * !!!! Before refactoring this method remember:
     * "premature optimization is the root of all evil" Donald Knuth
     */
    static CompletableFuture<byte[]> parseByLine(
            AsynchronousFileChannel asyncFile, byte[] bytes,
            int position,
            ByteBuffer buffer,
            StringBuilder res,
            Subscriber<? super String> sub)
    {
        if(bytes.length == 0)
            return closeAndNotifiesCompletion(asyncFile, bytes, sub);

        res.append(new String(bytes, UTF_8));
        if(res.indexOf("\n") < 0 && bytes.length >= buffer.capacity()) {
            // There is NO new line in res string. Thus proceed to read next chunk of bytes.
            return lines(asyncFile, position + bytes.length, buffer.clear(), res, sub);
        }
        /**
         * Notifies subscriber with lines
         */
        Iterator<String> iter = NEWLINE.splitAsStream(res).iterator();
        String remaining = null;
        while(iter.hasNext()) {
            String line = iter.next();
            if(!iter.hasNext() && line.indexOf("\n") < 0) {
                // This is the last sentence and has NO newline char.
                // So we do not want to notify it in onNext() and
                // we put it on remaining for the next iteration.
                remaining = line;
            } else {
                // Remove the newline char.
                line = line.substring(0, line.length() - 1);
                sub.onNext(line);
            }
        }
        /**
         * Call lines() recursively for the remaining of the string
         */
        if(bytes.length < buffer.capacity()) {
            /**
             * Already reaches the end of the file.
             */
            if(remaining != null)
                sub.onNext(remaining); // So notify last string
            return closeAndNotifiesCompletion(asyncFile, bytes, sub);
        }
        else {
            res = remaining == null
                    ? new StringBuilder()
                    : new StringBuilder(remaining);
            /**
             * Continue reading the file.
             */
            return lines(asyncFile, position + bytes.length, buffer.clear(), res, sub);
        }
    }

    static CompletableFuture<byte[]> readBytes(
            AsynchronousFileChannel asyncFile,
            ByteBuffer buffer,
            int position)
    {
        CompletableFuture<byte[]> promise = new CompletableFuture<>();
        asyncFile.read(buffer, position, buffer, new CompletionHandler<Integer, ByteBuffer>() {
            @Override
            public void completed(Integer result, ByteBuffer attachment) {
                attachment.flip();
                byte[] data = new byte[attachment.limit()]; // limit = result
                attachment.get(data);
                promise.complete(data);
            }

            @Override
            public void failed(Throwable exc, ByteBuffer attachment) {
                promise.completeExceptionally(exc);
            }
        });
        return promise;
    }

    /**
     * Reads the file from the beginning using
     * an AsyncFileChannel with a ByteBuffer of
     * 1024 capacity.
     * It automatically closes the underlying AsyncFileChannel
     * when read is complete.
     */
    public static CompletableFuture<String> readAll(String file) {
        return readAll(file, 1024);
    }

    /**
     * Reads the file from the beginning using
     * an AsyncFileChannel with a ByteBuffer of
     * the specified bufferSize capacity.
     * It automatically closes the underlying AsyncFileChannel
     * when read is complete.
     */
    public static CompletableFuture<String> readAll(String file, int bufferSize) {
        return readAll(Paths.get(file), bufferSize);
    }

    /**
     * Reads the file from the beginning using
     * an AsyncFileChannel with a ByteBuffer of
     * 1024 capacity.
     * It automatically closes the underlying AsyncFileChannel
     * when read is complete.
     */
    public static CompletableFuture<String> readAll(Path file) {
        return readAll(file, 1024);
    }

    /**
     * Reads the file from the beginning using
     * an AsyncFileChannel with a ByteBuffer of
     * the specified bufferSize capacity.
     * It automatically closes the underlying AsyncFileChannel
     * when read is complete.
     */
    public static CompletableFuture<String> readAll(Path file, int bufferSize) {
        return AsyncFileReader
                    .readAllBytes(file, bufferSize)
                    .thenApply(bytes -> new String(bytes, UTF_8));
    }

    /**
     * Reads all bytes from the beginning of the file
     * using an AsyncFileChannel with a ByteBuffer of
     * 1024 capacity.
     */
    public static CompletableFuture<byte[]> readAllBytes(Path file) {
        return readAllBytes(file, 1024);
    }

    /**
     * Reads all bytes from the beginning of the file
     * using an AsyncFileChannel with a ByteBuffer of
     * the specified bufferSize capacity.
     */
    public static CompletableFuture<byte[]> readAllBytes(
            Path file,
            int bufferSize,
            StandardOpenOption...options)
    {
        ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AsynchronousFileChannel asyncFile = openFileChannel(file, options);
        return readAllBytes(asyncFile, buffer, 0, out)
                .whenComplete((pos, ex) -> closeAfc(asyncFile))
                .thenApply(position -> out.toByteArray());
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

    private static void closeAfc(AsynchronousFileChannel asyncFile) {
        try {
            asyncFile.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static CompletableFuture<byte[]> closeAndNotifiesCompletion(AsynchronousFileChannel asyncFile, byte[] bytes, Subscriber<? super String> sub) {
        try {
            asyncFile.close();
            sub.onComplete(); // Successful terminal state.
        } catch (IOException e) {
            sub.onError(e); // Failed terminal state.
        }
        return completedFuture(bytes);
    }

    private static void write(ByteArrayOutputStream out, byte[] data) {
        try {
            out.write(data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static AsynchronousFileChannel openFileChannel(Path file, StandardOpenOption[] options) {
        try {
            return open(file, options);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
