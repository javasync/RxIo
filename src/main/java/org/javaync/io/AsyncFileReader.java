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
import java.util.Spliterator;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import static java.lang.System.lineSeparator;
import static java.nio.channels.AsynchronousFileChannel.open;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Spliterators.spliteratorUnknownSize;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.joining;
import static java.util.stream.StreamSupport.stream;

/**
 * Asynchronous non-blocking read operations with a reactive based API.
 * All read operations return a CompletableFuture with a single String or a
 * Publisher of strings corresponding to lines.
 * These operations use an underlying AsynchronousFileChannel.
 * All methods are asynchronous including the close() which chains a continuation
 * on last resulting read CompletableFuture to close the AsyncFileChannel on completion.
 */
public class AsyncFileReader implements AutoCloseable {

    static final Pattern NEWLINE = Pattern.compile(lineSeparator());

    final AsynchronousFileChannel asyncFile;
    /**
     * File position after last read operation completion.
     */
    CompletableFuture<Integer> pos = CompletableFuture.completedFuture(0);

    public AsyncFileReader(AsynchronousFileChannel asyncFile) {
        this.asyncFile = asyncFile;
    }

    public AsyncFileReader(Path file, StandardOpenOption...options) {
        try {
            asyncFile = open(file, options);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public AsyncFileReader(Path file) {
        this(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    public AsyncFileReader(String path, StandardOpenOption...options) {
        this(Paths.get(path), options);
    }

    public AsyncFileReader(String path) {
        this(Paths.get(path), StandardOpenOption.READ);
    }

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
        try {
            AsynchronousFileChannel asyncFile = open(file, options);
            ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
            return sub -> lines(asyncFile, 0, buffer, new StringBuilder(), sub);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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

    static private CompletableFuture<byte[]> parseByLine(
            AsynchronousFileChannel asyncFile, byte[] bytes,
            int position,
            ByteBuffer buffer,
            StringBuilder res,
            Subscriber<? super String> sub)
    {
        if(bytes.length == 0) return closeAndNotifiesCompletion(asyncFile, bytes, sub);

        res.append(new String(bytes, UTF_8));
        if(res.indexOf(lineSeparator()) < 0 && bytes.length >= buffer.capacity()) {
            // There is NO new line in res string. Thus proceed to read next chunk of bytes.
            return lines(asyncFile, position + bytes.length, buffer.clear(), res, sub);
        }
        /**
         * Notifies subscriber with a line
         */
        Iterator<String> iter = NEWLINE.splitAsStream(res).iterator();
        String line = iter.next();
        sub.onNext(line);
        /**
         * Call lines() recursively for the rest of the string
         */
        Spliterator<String> rest = spliteratorUnknownSize(iter, Spliterator.ORDERED);
        String restString = stream(rest, false).collect(joining(lineSeparator()));
        if(bytes.length < buffer.capacity()) {
            /**
             * Already reaches the end of the file.
             */
            if(restString != null && !restString.equals(""))
                sub.onNext(restString); // Notify last string if exist
            return closeAndNotifiesCompletion(asyncFile, bytes, sub);
        }
        else {
            /**
             * Continue reading the file.
             */
            return lines(asyncFile, position + bytes.length, buffer.clear(), new StringBuilder(restString), sub);
        }
    }

    static CompletableFuture<byte[]> readBytes(
            AsynchronousFileChannel asyncFile,
            ByteBuffer buf,
            int position)
    {
        CompletableFuture<byte[]> promise = new CompletableFuture<>();
        asyncFile.read(buf, position, buf, new CompletionHandler<Integer, ByteBuffer>() {
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
     */
    public CompletableFuture<String> readAll() {
        return readAll(1024);
    }

    /**
     * Reads the file from the beginning using
     * an AsyncFileChannel with a ByteBuffer of
     * the specified bufferSize capacity.
     * Converts the resulting byte array into a String.
     */
    public CompletableFuture<String> readAll(int bufferSize) {
        return readAllBytes(bufferSize)
                .thenApply(bytes -> new String(bytes, UTF_8));
    }

    /**
     * Reads all bytes from the beginning of the file
     * using an AsyncFileChannel with a ByteBuffer of
     * 1024 capacity.
     */
    public CompletableFuture<byte[]> readAllBytes() {
        return readAllBytes(1024);
    }

    /**
     * Reads all bytes from the beginning of the file
     * using an AsyncFileChannel with a ByteBuffer of
     * the specified bufferSize capacity.
     */
    public CompletableFuture<byte[]> readAllBytes(int bufferSize) {
        ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        /**
         * Wee need to update pos field.
         * The pos field is used on close() method, which chains
         * a continuation to close the asyncFile.
         */
        pos = readAllBytes(0, buffer, out);
        return pos.thenApply(position -> out.toByteArray());

    }

    CompletableFuture<Integer> readAllBytes(
            int position,
            ByteBuffer buffer,
            ByteArrayOutputStream out)
    {
        return  readToByteArrayStream(asyncFile, buffer, position, out)
                        .thenCompose(index ->
                                index < 0
                                ? completedFuture(position)
                                : readAllBytes(position + index, buffer.clear(), out));

    }

    static CompletableFuture<Integer> readToByteArrayStream(
            AsynchronousFileChannel asyncFile,
            ByteBuffer buf,
            int position,
            ByteArrayOutputStream out)
    {
        CompletableFuture<Integer> promise = new CompletableFuture<>();
        asyncFile.read(buf, position, buf, new CompletionHandler<Integer, ByteBuffer>() {
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

    /**
     * Asynchronous close operation.
     * Chains a continuation on CompletableFuture resulting from last read operation,
     * which closes the AsyncFileChannel on completion.
     * @throws IOException
     */
    @Override
    public void close() throws IOException {
        if(asyncFile != null) {
            pos.whenComplete((res, ex) ->
                    closeAfc(asyncFile)
            );
        }
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

}
