package org.javaync.io

import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.sendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import java.nio.channels.AsynchronousFileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class AsyncFileReaderFlow(private val channel: ProducerScope<String>) : AbstractAsyncFileReaderLines() {

    companion object {
        @JvmStatic fun lines(file: Path) : Flow<String> = lines(BUFFER_SIZE, file, StandardOpenOption.READ)

        @JvmStatic fun lines(bufferSize: Int, file: Path, vararg options: StandardOpenOption) : Flow<String> =
            callbackFlow<String> {
                val asyncFile = AsynchronousFileChannel.open(file, *options)
                val cf = AsyncFileReaderFlow(this).readLines(asyncFile, bufferSize)
                /**
                 * Next awaitClose() is equivalent to continuation.invokeOnCancellation ans it is mandatory.
                 * Should be used in the end of callbackFlow block.
                 */
                awaitClose {
                    cf.cancel(true)
                }
            }
            .buffer(bufferSize)
    }

    override fun onError(error: Throwable?) {
        channel.close(error)
    }

    override fun onComplete() {
        channel.close()
    }

    /**
     * Invoke from an IO background thread.
     */
    override fun onProduceLine(line: String) {
        /**
         * Replacing the following sendBlocking() with offer() (which is async)
         * will require the use of a chained buffer() in resulting Flow pipeline
         * to avoid loosing emitted values.
         * The sendBlocking() approach is throwing AbortFlowException, apparently
         * maybe because something has been emitted after cancellation request.
         * It happens with takeWhile(). Maybe we can reproduce it a in small scenario.
         */
        channel.offer(line)
        // channel.sendBlocking(line)
    }
}