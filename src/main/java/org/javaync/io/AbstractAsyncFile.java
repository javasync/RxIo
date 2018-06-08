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
import java.nio.channels.AsynchronousFileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;

import static java.nio.channels.AsynchronousFileChannel.open;

public class AbstractAsyncFile implements AutoCloseable {

    final AsynchronousFileChannel asyncFile;
    CompletableFuture<Integer> pos = CompletableFuture.completedFuture(0);

    public AbstractAsyncFile(AsynchronousFileChannel asyncFile) {
        this.asyncFile = asyncFile;
    }

    public AbstractAsyncFile(Path file, StandardOpenOption...options) {
        try {
            asyncFile = open(file, options);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public AbstractAsyncFile(Path file) {
        this(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    public AbstractAsyncFile(String path, StandardOpenOption...options) {
        this(Paths.get(path), options);
    }

    public AbstractAsyncFile(String path) {
        this(Paths.get(path), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

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
}
