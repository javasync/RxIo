package org.javaync.io

import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import java.nio.file.Path

class AsyncFileReaderFlow(private val channel: ProducerScope<String>) : Subscriber<String> {

    private lateinit var sign: Subscription

    companion object {
        @JvmStatic fun lines(file: Path) : Flow<String> =
            callbackFlow {
                val sub = AsyncFileReaderFlow(this)
                AsyncFiles.lines(file).subscribe(sub)
                /**
                 * Next awaitClose() is equivalent to continuation.invokeOnCancellation and it is mandatory.
                 * Should be used in the end of callbackFlow block.
                 */
                awaitClose {
                    sub.sign.cancel()
                }
            }
            .buffer(AbstractAsyncFileReaderLines.BUFFER_SIZE)
    }

    override fun onError(error: Throwable?) {
        channel.close(error)
    }

    override fun onComplete() {
        channel.close()
    }

    override fun onSubscribe(subscription: Subscription) {
        sign = subscription
        sign.request(Long.MAX_VALUE);
    }

    override fun onNext(line: String) {
        /**
         * Replacing the following sendBlocking() with offer() (which is async)
         * will require the use of a chained buffer() in resulting Flow pipeline
         * to avoid loosing emitted values.
         * The sendBlocking() approach is throwing AbortFlowException, apparently
         * maybe because something has been emitted after cancellation request.
         * It happens with takeWhile(). Maybe we can reproduce it a in small scenario.
         */
        channel.trySend(line)
        // channel.sendBlocking(line)
    }
}
