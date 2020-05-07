package org.javasync.util;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.function.Consumer;

public class SubscriberBuilder<T> implements Subscriber<T> {

    private final Subscriber<T> sub;

    public SubscriberBuilder(Subscriber<T> sub) {
        this.sub = sub;
    }

    public SubscriberBuilder<T> doOnSubscribe(Consumer<Subscription> cons) {
        return new AddOnSubscribe<>(cons, this);
    }

    public SubscriberBuilder<T> doOnError(Consumer<Throwable> cons) {
        return new AddOnError<>(cons, this);
    }

    public SubscriberBuilder<T> doOnComplete(Runnable action) {
        return new AddOnComplete<>(action, this);
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        sub.onSubscribe(subscription);
    }

    @Override
    public void onNext(T item) {
        sub.onNext(item);
    }

    @Override
    public void onError(Throwable throwable) {
        sub.onError(throwable);
    }

    @Override
    public void onComplete() {
        sub.onComplete();
    }
}
