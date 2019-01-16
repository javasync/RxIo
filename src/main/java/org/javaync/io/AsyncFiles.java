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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

import static java.nio.channels.AsynchronousFileChannel.open;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Asynchronous non-blocking read and write operations with a reactive based API.
 * Read operations return a CompletableFuture with a single String or a Publisher
 * of strings corresponding to lines.
 * Write methods return a CompletableFuture with the final file index after the
 * completion of corresponding write operation.
 * These operations use an underlying AsynchronousFileChannel.
 */

public class AsyncFiles {

    static final int BUFFER_SIZE = 262144;

    private AsyncFiles() {
    }

    /**
     * Reads the given file from the beginning using an AsyncFileChannel
     * with a ByteBuffer of {@link AsyncFiles#BUFFER_SIZE BUFFER_SIZE} capacity.
     */
    public static Publisher<String> lines(String file) {
        return lines(BUFFER_SIZE, Paths.get(file));
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
        return sub -> {
            AsynchronousFileChannel asyncFile = openFileChannel(file, options);
            ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
            AsyncFileReader.readLinesToSubscriber(asyncFile, 0, buffer, new StringBuilder(), sub);
        };
    }

    /**
     * Reads the file from the beginning using an AsyncFileChannel
     * with a ByteBuffer of {@link AsyncFiles#BUFFER_SIZE BUFFER_SIZE} capacity.
     * It automatically closes the underlying AsyncFileChannel when read is complete.
     */
    public static CompletableFuture<String> readAll(String file) {
        return readAll(Paths.get(file));
    }

    /**
     * A callback based version of readAll().
     * Reads the file from the beginning using an AsyncFileChannel
     * with a ByteBuffer of {@link AsyncFiles#BUFFER_SIZE BUFFER_SIZE} capacity.
     * It automatically closes the underlying AsyncFileChannel when read is complete.
     */
    public static void readAll(String file, BiConsumer<Throwable, String> callback) {
        readAll(file, BUFFER_SIZE)
            .thenAcceptAsync(data -> callback.accept(null, data))
            .exceptionally(err -> {
                callback.accept(err, null);
                throw new RuntimeException(err);
            });
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
     * Reads the file from the beginning using an AsyncFileChannel
     * with a ByteBuffer of {@link AsyncFiles#BUFFER_SIZE BUFFER_SIZE} capacity.
     * It automatically closes the underlying AsyncFileChannel
     * when read is complete.
     */
    public static CompletableFuture<String> readAll(Path file) {
        return readAll(file, BUFFER_SIZE);
    }

    /**
     * Reads the file from the beginning using
     * an AsyncFileChannel with a ByteBuffer of
     * the specified bufferSize capacity.
     * It automatically closes the underlying AsyncFileChannel
     * when read is complete.
     */
    public static CompletableFuture<String> readAll(Path file, int bufferSize) {
        return readAllBytes(file, bufferSize)
                .thenApply(bytes -> new String(bytes, UTF_8));
    }

    /**
     * Reads all bytes from the beginning of the file using an AsyncFileChannel
     * with a ByteBuffer of {@link AsyncFiles#BUFFER_SIZE BUFFER_SIZE} capacity.
     */
    public static CompletableFuture<byte[]> readAllBytes(Path file) {
        return readAllBytes(file, BUFFER_SIZE);
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
        CompletableFuture<byte[]> bytes = AsyncFileReader
            .readAllBytes(asyncFile, buffer, 0, out)
            .thenApply(position -> out.toByteArray());
        /**
         * Deliberately chained in this way.
         */
        bytes.whenCompleteAsync((pos, ex) -> closeAfc(asyncFile));
        return bytes;
    }

    /**
     * Writes bytes to a file.
     * The options parameter specifies how the file is created or opened.
     * All bytes in the byte array are written to the file.
     * The method ensures that the file is closed when all bytes have been
     * written (or an I/O error or other runtime exception is thrown).
     * Returns a CompletableFuture with the final file index
     * after the completion of the corresponding write operation.
     * If an I/O error occurs then it may complete the resulting CompletableFuture
     * exceptionally.
     */
    public static CompletableFuture<Integer> writeBytes(
            Path path,
            byte[] bytes)
    {
        return writeBytes(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    /**
     * Writes bytes to a file.
     * The options parameter specifies how the file is created or opened.
     * All bytes in the byte array are written to the file.
     * The method ensures that the file is closed when all bytes have been
     * written (or an I/O error or other runtime exception is thrown).
     * Returns a CompletableFuture with the final file index
     * after the completion of the corresponding write operation.
     * If an I/O error occurs then it may complete the resulting CompletableFuture
     * exceptionally.
     */
    public static CompletableFuture<Integer> writeBytes(
            Path path,
            byte[] bytes,
            StandardOpenOption... options)
    {
        try (AsyncFileWriter writer = new AsyncFileWriter(path, options)) {
            writer.write(bytes);
            // The call to writer.close() is asynchronous and will chain
            // a continuation to close the AsyncFileChannel only after completion.
            return writer.getPosition();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Write lines of text to a file. Each line is a char sequence and
     * is written to the file in sequence with each line terminated by
     * the platform's line separator, as defined by the system property
     * line.separator.
     * Returns a CompletableFuture with the final file index
     * after the completion of the corresponding write operation.
     */
    public static CompletableFuture<Integer> write(
            Path path,
            Iterable<? extends CharSequence> lines)
    {
        return write(path, lines, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    /**
     * Write lines of text to a file. Each line is a char sequence and
     * is written to the file in sequence with each line terminated by
     * the platform's line separator, as defined by the system property
     * line.separator.
     * Returns a CompletableFuture with the final file index
     * after the completion of the corresponding write operation.
     */
    public static CompletableFuture<Integer> write(
            Path path,
            Iterable<? extends CharSequence> lines,
            StandardOpenOption... options)
    {
        try (AsyncFileWriter writer = new AsyncFileWriter(path, options)) {
            lines.forEach(writer::writeLine);
            // The call to writer.close() is asynchronous and will chain
            // a continuation to close the AsyncFileChannel only after completion.
            return writer.getPosition();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }


    static AsynchronousFileChannel openFileChannel(Path file, StandardOpenOption[] options) {
        try {
            return open(file, options);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }


    static void closeAfc(AsynchronousFileChannel asyncFile) {
        try {
            asyncFile.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
