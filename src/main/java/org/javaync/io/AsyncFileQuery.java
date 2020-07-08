package org.javaync.io;

import org.jayield.AsyncQuery;

import java.io.IOException;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

import static java.nio.channels.AsynchronousFileChannel.open;

public class AsyncFileQuery extends AsyncQuery<String> {
    private final Path file;

    public AsyncFileQuery(Path file) {
        this.file = file;
    }

    @Override
    public CompletableFuture<Void> subscribe(BiConsumer<? super String, ? super Throwable> cons) {
        try {
            AbstractAsyncFileReaderLines reader = new ReadToCallback(cons);
            AsynchronousFileChannel asyncFile = open(file, StandardOpenOption.READ);
            return reader.readLines(asyncFile, AbstractAsyncFileReaderLines.BUFFER_SIZE);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static class ReadToCallback extends AbstractAsyncFileReaderLines {
        private final BiConsumer<? super String, ? super Throwable> cons;

        public ReadToCallback(BiConsumer<? super String, ? super Throwable> cons) {
            this.cons = cons;
        }

        @Override
        protected void onError(Throwable error) {
            cons.accept(null, error);
        }

        @Override
        protected void onComplete() {

        }

        @Override
        protected void onProduceLine(String line) {
            cons.accept(line, null);
        }
    }
}
