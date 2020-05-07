package org.javasync.util;

import org.reactivestreams.Subscriber;

import java.util.function.Consumer;

public class AddOnNext<T> extends SubscriberBuilder<T> implements Subscriber<T>{

    private final Consumer<T> cons;

    public AddOnNext(Consumer<T> cons) {
        super(new EmptySubscriber<>());
        this.cons = cons;
    }

    @Override
    public void onNext(T item) {
        cons.accept(item);
    }
}
