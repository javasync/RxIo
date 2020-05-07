package org.javasync.util;

import org.reactivestreams.Subscription;

/**
 * For subscribers pipeline composition.
 * This should the leaf of the pipeline.
 * This avoids to check if next is null.
 * @param <T>
 */
public class EmptySubscriber<T> implements org.reactivestreams.Subscriber<T> {
    @Override
    public void onSubscribe(Subscription subscription) {
        /**
         * Do nothing. This is the leaf of Subscribers pipeline.
         */
    }

    @Override
    public void onNext(T t) {
        /**
         * Do nothing. This is the leaf of Subscribers pipeline.
         */
    }

    @Override
    public void onError(Throwable throwable) {
        /**
         * Do nothing. This is the leaf of Subscribers pipeline.
         */
    }

    @Override
    public void onComplete() {
        /**
         * Do nothing. This is the leaf of Subscribers pipeline.
         */
    }
}
