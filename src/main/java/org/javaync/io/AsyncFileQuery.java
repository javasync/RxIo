package org.javaync.io;

import org.jayield.AsyncQuery;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

public class AsyncFileQuery extends AsyncQuery<String> {
    private final Path file;

    public AsyncFileQuery(Path file) {
        this.file = file;
    }

    @Override
    public CompletableFuture<Void> subscribe(BiConsumer<? super String, ? super Throwable> cons) {
        CompletableFuture<Void> cf = new CompletableFuture<>();
        ReadToCallback reader = new ReadToCallback(cons, cf);
        AsyncFiles.lines(file).subscribe(reader);
        return cf;
    }

    private static class ReadToCallback implements Subscriber<String> {
        private final BiConsumer<? super String, ? super Throwable> cons;
        private final CompletableFuture<Void> cf;
        private Subscription sign;

        public ReadToCallback(BiConsumer<? super String, ? super Throwable> cons, CompletableFuture<Void> cf) {
            this.cons = cons;
            // Whenever the client express intention of finishing emission
            // either through complete() or cancel() then we propagate to
            // subscription cancellation.
            this.cf = cf; // Keep the reference to the same CF shared with the client
            cf.whenComplete((nothing, err) -> sign.cancel());
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            this.sign = subscription;
            sign.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(String line) {
            cons.accept(line, null);
        }

        @Override
        public void onError(Throwable err) {
            cons.accept(null, err);
        }

        @Override
        public void onComplete() {
            if(!cf.isDone())
                cf.complete(null);
        }
    }
}
