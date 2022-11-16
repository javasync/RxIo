package org.javaync.io

import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Reads the file from the beginning using an {@link AsynchronousFileChannel}
 * with a ByteBuffer of {@link AbstractAsyncFileReaderLines#BUFFER_SIZE BUFFER_SIZE} capacity.
 * It automatically closes the underlying AsyncFileChannel when read is complete.
 */
suspend fun readAll(file: String) = readAll(Paths.get(file))

/**
 * Reads the file from the beginning using an {@link AsynchronousFileChannel}
 * with a ByteBuffer of {@link AbstractAsyncFileReaderLines#BUFFER_SIZE BUFFER_SIZE} capacity.
 * It automatically closes the underlying AsyncFileChannel when read is complete.
 */
suspend fun readAll(path: Path) = AsyncFiles
    .readAll(path)
    .await()

/**
 * Writes text to a file. Creates new or replace it if already exist.
 * The method ensures that the file is closed when all bytes have been
 * written (or an I/O error or other runtime exception is thrown).
 * Returns the final file index after the completion of the corresponding write operation.
 * If an I/O error occurs then it may complete exceptionally.
 */
suspend fun writeText(file: String, text: String) = writeText(Paths.get(file), text)

/**
 * Writes text to a file. Creates new or replace it if already exist.
 * The method ensures that the file is closed when all bytes have been
 * written (or an I/O error or other runtime exception is thrown).
 * Returns the final file index after the completion of the corresponding write operation.
 * If an I/O error occurs then it may complete exceptionally.
 */
suspend fun writeText(path: Path, text: String) = AsyncFiles
    .writeBytes(path, text.toByteArray(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    .await()

suspend fun <T> CompletableFuture<T>.await(): T =
    suspendCoroutine<T> { cont: Continuation<T> ->
        whenComplete { result, exception ->
            if (exception == null) // the future has been completed normally
                cont.resume(result)
            else // the future has completed with an exception
                cont.resumeWithException(exception)
        }
    }
