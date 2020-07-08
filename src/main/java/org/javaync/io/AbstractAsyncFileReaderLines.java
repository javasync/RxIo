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
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import java.security.InvalidParameterException;
import java.util.concurrent.CompletableFuture;
import java.util.function.ObjIntConsumer;

/**
 * Asynchronous non-blocking read operations that use an underlying AsynchronousFileChannel.
 */
public abstract class AbstractAsyncFileReaderLines {
    static final int BUFFER_SIZE= 4096*8;
    private static final int MAX_LINE_SIZE = 4096;
    private static final int LF = '\n';
    private static final int CR = '\r';

    protected abstract void onError(Throwable error);
    protected abstract void onComplete();
    protected abstract void onProduceLine(String line);

    /**
     * Read bytes from an {@code AsynchronousFileChannel}, which are decoded into characters
     * using the UTF-8 charset.
     * The resulting characters are parsed by line and passed to the destination buffer.
     * @param asyncFile the nio associated file channel.
     * @param bufferSize
     * @return
     */
    final CompletableFuture<Void> readLines(
        AsynchronousFileChannel asyncFile,
        int bufferSize)
    {
        CompletableFuture<Void> finish = new CompletableFuture<>();
        readLines(asyncFile, 0, 0, 0, new byte[bufferSize], new byte[MAX_LINE_SIZE], 0, finish);
        return finish;
    }
    /**
     * There is a recursion on `readLines()`establishing a serial order among:
     * `readLines()` -> `produceLine()` -> `onProduceLine()` -> `readLines()` -> and so on.
     * It finishes with a call to `close()`.
     *
     * @param asyncFile the nio associated file channel.
     * @param position current read or write position in file.
     * @param bufpos read position in buffer.
     * @param bufsize total bytes in buffer.
     * @param buffer buffer for current producing line.
     * @param auxline the transfer buffer.
     * @param linepos current position in producing line.
     * @param finish Notifies completion and receives cancellation request.
     */
    private final void readLines(
            AsynchronousFileChannel asyncFile,
            long position,
            int bufpos,
            int bufsize,
            byte[] buffer,
            byte[] auxline,
            int linepos,
            CompletableFuture<Void> finish)
    {
        while(bufpos < bufsize) {
            if (buffer[bufpos] == LF) {
                if (linepos > 0 && auxline[linepos-1] == CR) linepos--;
                bufpos++;
                // If CF finish has been canceled then produceLine() returns false and we will end the loop.
                if(!produceLine(auxline, linepos, asyncFile, finish)) return;
                linepos = 0;
            }
            else if (linepos == MAX_LINE_SIZE -1) {
                // If CF finish has been canceled then produceLine() returns false and we will end the loop.
                if(!produceLine(auxline, linepos, asyncFile, finish)) return;
                linepos = 0;
            }
            else auxline[linepos++] = buffer[bufpos++];
        }
        int lastLinePos = linepos;
        readBytes(asyncFile, position, buffer, 0, buffer.length, (err, res) -> {
            if(err != null) {
                onError(err);
                close(asyncFile, finish);
                return;
            }
            long next = position;
            if (res > 0) next += res;
            if (res <= 0) {
                // needed for last line that doesn't end with LF
                if (lastLinePos > 0) {
                   produceLine(auxline, lastLinePos, asyncFile, finish); // Last line, thus no check needed!
                }
                // Following, it sets hasNext to false.
                close(asyncFile, finish);
                return;
            }
            else readLines(asyncFile, next, 0, res, buffer, auxline, lastLinePos, finish);
        });
    }
    /**
     * Asynchronous read chunk operation, callback based.
     */
    private static final void readBytes(
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

    /**
     * Performed from the IO background thread when it reached the end of the file.
     *
     * @param asyncFile
     */
    private final void close(AsynchronousFileChannel asyncFile, CompletableFuture<Void> finish) {
        try {
            asyncFile.close();
        } catch (IOException e) {
            onError(e); // Failed terminal state.
            // Emission has finished. Does not propagate error on CompletableFuture.
        } finally {
            onComplete();
            if(!finish.isDone())
                finish.complete(null);
        }
    }
    /**
     * This is called only from readLines() callback and performed from a background IO thread.
     *
     * @param auxline the transfer buffer.
     * @param linepos current position in producing line.
     * @param asyncFile the nio associated file channel.
     * @param finish CompletableFuture signaling the end of emission or cancellation.
     * @return true or false depending on whether the line is emitted or emission was canceled by the client.
     */
    private final boolean produceLine(
        byte[] auxline,
        int linepos,
        AsynchronousFileChannel asyncFile,
        CompletableFuture<Void> finish)
    {
        if(finish.isCancelled()) {
            close(asyncFile, finish);
            return false;
        } else {
            String line = new String(auxline, 0, linepos, StandardCharsets.UTF_8);
            onProduceLine(line);
            return true;
        }
    }
}
