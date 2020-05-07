package org.javasync.util;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.function.Consumer;

public class AddOnSubscribe<T> extends SubscriberBuilder<T> implements Subscriber<T> {
    private final Consumer<Subscription> cons;

    public AddOnSubscribe(Consumer<Subscription> cons, Subscriber<T> sub) {
        super(sub);
        this.cons = cons;
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        super.onSubscribe(subscription);
        cons.accept(subscription);
    }
}
