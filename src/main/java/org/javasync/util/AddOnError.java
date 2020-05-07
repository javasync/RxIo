package org.javasync.util;

import org.reactivestreams.Subscriber;

import java.util.function.Consumer;

public class AddOnError<T> extends SubscriberBuilder<T> implements Subscriber<T> {

    private final Consumer<Throwable> cons;

    public AddOnError(Consumer<Throwable> cons, Subscriber<T> sub) {
        super(sub);
        this.cons = cons;
    }

    @Override
    public void onNext(T item) {
        try{
            super.onNext(item);
        } catch (Exception err) {
            this.onError(err);
        }
    }
    @Override
    public void onError(Throwable throwable) {
        super.onError(throwable);
        cons.accept(throwable);
    }
}
