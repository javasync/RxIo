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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;

import static java.nio.ByteBuffer.wrap;

public class AsyncFileWriter extends AbstractAsyncFile {

    public AsyncFileWriter(AsynchronousFileChannel asyncFile) {
        super(asyncFile);
    }

    public AsyncFileWriter(Path file, StandardOpenOption...options) {
        super(file, options);
    }

    public AsyncFileWriter(Path file) {
        this(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    public AsyncFileWriter(String path, StandardOpenOption...options) {
        this(Paths.get(path), options);
    }

    public AsyncFileWriter(String path) {
        this(Paths.get(path), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    public CompletableFuture<Integer> writeLine(String str) {
        try {
            return write(str + System.lineSeparator());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public CompletableFuture<Integer> write(String str) throws IOException {
        byte[] data = (str).getBytes();
        pos = pos.thenCompose(index -> {
            CompletableFuture<Integer> size = write(asyncFile, wrap(data), index);
            return size.thenApply(length -> length + index);
        });
        return pos;
    }

    static CompletableFuture<Integer> write(
            AsynchronousFileChannel asyncFile,
            ByteBuffer buf,
            int position)
    {
        CompletableFuture<Integer> promise = new CompletableFuture<>();
        asyncFile.write(buf, position, null, new CompletionHandler<Integer, Object>() {
            @Override
            public void completed(Integer result, Object attachment) {
                promise.complete(result);
            }

            @Override
            public void failed(Throwable exc, Object attachment) {
                promise.completeExceptionally(exc);
            }
        });
        return promise;
    }
}
