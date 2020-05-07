package org.javasync.util;

import org.reactivestreams.Subscriber;

public class AddOnComplete<T> extends SubscriberBuilder<T> {

    private final Runnable action;

    public AddOnComplete(Runnable action, Subscriber<T> sub) {

        super(sub);
        this.action = action;
    }

    @Override
    public void onComplete() {
        super.onComplete();
        action.run();
    }
}
