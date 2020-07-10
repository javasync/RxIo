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

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.nio.channels.AsynchronousFileChannel;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Asynchronous non-blocking read operations with a reactive based API
 * implementing the Subscription proposal from reactivestreams.
 */
public class AsyncFileReaderLines extends AbstractAsyncFileReaderLines implements Subscription {
    //
    // We need a reference to the `Subscriber` so we can talk to it.
    private final Subscriber<? super String> sub;
    //
    // We are using this `AtomicBoolean` to make sure that this `Subscription` doesn't run concurrently with itself,
    // which would violate rule 1.3 among others (no concurrent notifications).
    // Possible states: 0 (not emitting), 1 (emitting lines) and 3 (evaluating conditions requests and lines).
    // The onEmit changes from states 0 -> 1 <-> 3 -> 0.
    // It never changes from 1 to 0 directly. It must pass before by the state 3.
    private final AtomicInteger onEmit = new AtomicInteger(0);
    //
    // This `ConcurrentLinkedQueue` will track the `onNext` signals that will be sent to the `Subscriber`.
    private final ConcurrentLinkedDeque<String> lines = new ConcurrentLinkedDeque<>();
    //
    // Here we track the current demand, i.e. what has been requested but not yet delivered.
    private final AtomicLong requests = new AtomicLong();
    //
    // Need to keep track of End-of-Stream
    private boolean hasNext = true;

    AsyncFileReaderLines(Subscriber<? super String> sub, AsynchronousFileChannel asyncFile, int bufferSize) {
        this.sub = sub;
        this.readLines(asyncFile, bufferSize);
    }

    @Override
    protected void onError(Throwable error) {
        sub.onError(error);
    }
    /**
     * Performed from the IO background thread when it reached the end of the file.
     */
    @Override
    protected void onComplete() {
        hasNext = false;
        tryFlushPendingLines();
    }
    /**
     * This is called only from readLines() callback and performed from a background IO thread.
     */
    @Override
    protected void onProduceLine(String line) {
        /**
         * Always put the newly line into lines because a concurrent request
         * may be asking for new lines and we should ensure the total order.
         */
        lines.offer(line);
        /**
         * It only emits lines if subscription is not cancelled yet and there are still
         * pending requests.
         */
        while(!isCancelled()         // This makes sure that rule 1.8 is upheld, i.e. we need to stop signalling "eventually"
              && requests.get() > 0  // This makes sure that rule 1.1 is upheld (sending more than was demanded)
              && !lines.isEmpty()) {
            emitLine();
        }
    }
    /**
     * Emit a line to the Subscriber and decrement the number of pending requests.
     */
    private void emitLine() {
        String line = lines.poll();
        if(line != null) {
            sub.onNext(line);
            requests.decrementAndGet();
        } else {
            terminateDueTo(new IllegalStateException("Unexpected race occur on lines offer. No other thread should concurrently should be taking lines!"));
        }
    }
    /**
     * Implementation of `Subscription.request` registers that more elements are in demand.
     * This request() only try to emitLines() after the End-of-Stream, in which case we
     * should control mutual exclusion to the emitLines().
     * @param l
     */
    @Override
    public void request(long l) {
        if(isCancelled()) return;
        doRequest(l);
        if(!hasNext) {
            tryFlushPendingLines();
        }
    }
    /**
     * This method may be invoked by request() in case of End-of-Stream or by close().
     * Here the `onEmit` controls mutual exclusion to the emitLine() call.
     */
    private void tryFlushPendingLines() {
        int state;
        // Spin try for emission entry.
        // If it successfully change state from 0 to 1, then it will proceed to try to emit lines.
        // It quits if someone else is already emitting lines (in state 1).
        // If someone else is evaluating the conditions (state 3) then it will spin and retry.
        while ((state = onEmit.compareAndExchange(0,1)) > 0)
           if (state == 1) return; // give up
        //
        // Start emission (in state 1)
        while(toContinue()) {
            emitLine();
        }
        // End emission (in state 3)
        // Other thread entering at this moment in request() increments requests and
        // this thread does not see the new value of requests and do not emit pending lines.
        // Yet, the other thread will spin until onEmit change from 3 to 0 and then
        // it will change onEmit to 1 and proceed emitting pending lines.
        onEmit.set(0); // release onEmit
        if(lines.isEmpty()) {
            cancel();         // We need to consider this `Subscription` as cancelled as per rule 1.6
            sub.onComplete(); // Then we signal `onComplete` as per rule 1.2 and 1.5
        }
    }

    /**
     * Here it will change to state 3 (on evaluation) and then to state 1 (emitting)
     * if there are pending requests and lines to be emitted.
     */
    private boolean toContinue() {
        // First, change to state 3 corresponding to evaluation of requests and lines.
        onEmit.set(3);
        boolean cont = !isCancelled() // This makes sure that rule 1.8 is upheld, i.e. we need to stop signalling "eventually"
              && requests.get() > 0   // This makes sure that rule 1.1 is upheld (sending more than was demanded)
              && !lines.isEmpty();
        // If there are pending requests and lines to be emitted, then change to
        // state 1 that it will emit those lines.
        if (cont) onEmit.set(1);
        return cont;
    }

    /**
     * This method will register inbound demand from our `Subscriber` and validate it against rule 3.9 and rule 3.17
     */
    private void doRequest(final long n) {
        if (n < 1)
            terminateDueTo(new IllegalArgumentException(sub + " violated the Reactive Streams rule 3.9 by requesting a non-positive number of elements."));
        else if (requests.get() + n < 1) {
            // As governed by rule 3.17, when demand overflows `Long.MAX_VALUE` we treat the signalled demand as "effectively unbounded"
            requests.set(Long.MAX_VALUE);  // Here we protect from the overflow and treat it as "effectively unbounded"
        } else {
            requests.addAndGet(n); // Here we record the downstream demand
        }
    }

    /**
     * This is a helper method to ensure that we always `cancel` when we signal `onError` as per rule 1.6
     */
    private void terminateDueTo(final Throwable t) {
        cancel(); // When we signal onError, the subscription must be considered as cancelled, as per rule 1.6
        try {
            sub.onError(t); // Then we signal the error downstream, to the `Subscriber`
        } catch (final Exception t2) { // If `onError` throws an exception, this is a spec violation according to rule 1.9, and all we can do is to log it.
            Throwable ex = new IllegalStateException(sub + " violated the Reactive Streams rule 2.13 by throwing an exception from onError.", t2);
            Logger.getGlobal().log(Level.SEVERE, "Violated the Reactive Streams rule 2.13", ex);
        }
    }
}
