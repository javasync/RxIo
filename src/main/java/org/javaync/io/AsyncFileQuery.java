package org.javaync.io;

import org.jayield.AsyncQuery;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class AsyncFileQuery extends AsyncQuery<String> {
    private final Path file;

    public AsyncFileQuery(Path file) {
        this.file = file;
    }

    @Override
    public CompletableFuture<Void> subscribe(BiConsumer<? super String, ? super Throwable> cons) {
        /**
         * The following CF is used bidirectionally from the Reader to the user and vice-versa.
         * The Reader may notify the user about end completion and the user may tell the Reader
         * to stop read and invoke the callback when the CF is cancelled or completed by the user.
         */
        CompletableFuture<Void> cf = new CompletableFuture<>();
        try {
            ReaderToCallback
                .of(cons, () -> { if(!cf.isDone()) cf.complete(null); })
                .apply(reader -> cf.whenComplete((nothing, err) -> reader.cancel()))
                .readLines(file);
        } catch (IOException e) {
            cf.completeExceptionally(e);
        }
        return cf;
    }


    private static class ReaderToCallback extends AbstractAsyncFileReaderLines {
        private final BiConsumer<? super String, ? super Throwable> cons;
        private final Runnable doOnComplete;

        public ReaderToCallback(BiConsumer<? super String, ? super Throwable> cons, Runnable doOnComplete) {
            this.cons = cons;
            this.doOnComplete = doOnComplete;
        }

        public static ReaderToCallback of(BiConsumer<? super String, ? super Throwable> cons, Runnable doOnComplete) {
            return new ReaderToCallback(cons, doOnComplete);
        }

        public final AbstractAsyncFileReaderLines apply(Consumer<AbstractAsyncFileReaderLines> cons) {
            cons.accept(this);
            return this;
        }

        @Override
        public void onProduceLine(String line) {
            cons.accept(line, null);
        }

        @Override
        public void onError(Throwable err) {
            cons.accept(null, err);
        }

        @Override
        public void onComplete() {
            doOnComplete.run();
        }
    }
}
