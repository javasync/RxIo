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
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;

import static java.lang.System.lineSeparator;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Spliterators.spliteratorUnknownSize;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.joining;
import static java.util.stream.StreamSupport.stream;

public class AsyncFileReader extends AbstractAsyncFile {

    static final Pattern NEWLINE = Pattern.compile(lineSeparator());

    public AsyncFileReader(AsynchronousFileChannel asyncFile) {
        super(asyncFile);
    }

    public AsyncFileReader(Path file, StandardOpenOption...options) {
        super(file, options);
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
     * Reads all bytes from file using a ByteBuffer with the
     * specified bufferSize capacity and converts the resulting
     * array into a String.
     */
    public CompletableFuture<String> readAll(int bufferSize) {
        return readAllBytes(bufferSize)
                .thenApply(bytes -> new String(bytes, UTF_8));
    }

    /**
     * Reads all bytes from file and converts the resulting
     * array into a String.
     */
    public CompletableFuture<String> readAll() {
        return readAllBytes()
                .thenApply(bytes -> new String(bytes, UTF_8));
    }


    public Publisher<String> lines(int bufferSize) {
        ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
        return sub -> lines(0, buffer, new StringBuilder(), sub);
    }

    CompletableFuture<byte[]> lines(int position, ByteBuffer buffer, StringBuilder res, Subscriber<? super String> sub) {
        return readBytes(asyncFile, buffer, position)
                .thenCompose(bytes -> parseByLine(bytes, position, buffer, res, sub));
    }

    private CompletableFuture<byte[]> parseByLine(
            byte[] bytes,
            int position,
            ByteBuffer buffer,
            StringBuilder res,
            Subscriber<? super String> sub)
    {
        if(bytes.length == 0) {
            // Finishes reading file!
            sub.onComplete();
            return completedFuture(bytes);
        }
        res.append(new String(bytes, UTF_8));
        if(res.indexOf(lineSeparator()) < 0 && bytes.length >= buffer.capacity()) {
            // There is NO new line in res string. Thus proceed to read next chunk of bytes.
            return lines(position + bytes.length, buffer.clear(), res, sub);
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
             * Already reaches the end of the file
             */
            if(restString != null && !restString.equals(""))
                sub.onNext(restString);
            sub.onComplete();
            return completedFuture(bytes);
        }
        else
            return lines(position + bytes.length, buffer.clear(), new StringBuilder(restString), sub);
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
     * Reads all bytes from file using a ByteBuffer with 1024 capacity.
     */
    public CompletableFuture<byte[]> readAllBytes() {
        return readAllBytes(1024);
    }

    /**
     * Reads all bytes from file using a ByteBuffer with the
     * specified bufferSize capacity.
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

    private static void write(ByteArrayOutputStream out, byte[] data) {
        try {
            out.write(data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
