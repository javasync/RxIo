package org.javasync.util;

import org.reactivestreams.Subscription;

public class EmptySubscriber<T> implements org.reactivestreams.Subscriber<T> {
    @Override
    public void onSubscribe(Subscription subscription) {

    }

    @Override
    public void onNext(T t) {

    }

    @Override
    public void onError(Throwable throwable) {

    }

    @Override
    public void onComplete() {

    }
}
