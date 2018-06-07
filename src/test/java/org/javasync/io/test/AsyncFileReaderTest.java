/*
 * MIT License
 *
 * Copyright (c) 2018, Miguel Gamboa (gamboa.pt)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */

package org.javasync.io.test;

import org.javaync.io.AsyncFileReader;
import org.junit.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Flux;

import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.lang.System.lineSeparator;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.delete;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

public class AsyncFileReaderTest {

    static final Pattern NEWLINE = Pattern.compile(lineSeparator());


    @Test
    public void afwReadLinesWithReactorTest() throws IOException, InterruptedException {
        final String PATH = "output.txt";
        final List<String> expected = Arrays.asList("super", "brave", "isel", "ole", "gain", "massi", "tot");
        try (FileWriter writer = new FileWriter(PATH)) {
            expected.forEach(line -> write(writer, line));
        }
        try (AsyncFileReader reader = new AsyncFileReader(PATH)) {
            final CountDownLatch latch = new CountDownLatch(1);
            final Iterator<String> iter = expected.iterator();
            Flux
                    .from(reader.lines(4))
                    .doOnNext(line -> assertEquals(iter.next(), line))
                    .doOnComplete(latch::countDown)
                    .subscribe();
            latch.await();
            assertFalse("Missing items retrieved by lines subscriber!!", iter.hasNext());
        }finally {
            delete(Paths.get(PATH));
        }
    }

    @Test
    public void afwReadLinesTest() throws IOException, InterruptedException {
        final String PATH = "output.txt";
        final List<String> expected = Arrays.asList("super", "brave", "isel", "ole", "gain", "massi", "tot");
        try (FileWriter writer = new FileWriter(PATH)) {
            expected.forEach(line -> write(writer, line));
        }
        try (AsyncFileReader reader = new AsyncFileReader(PATH)) {
            final CountDownLatch latch = new CountDownLatch(1);
            Iterator<String> iter = expected.iterator();
            reader
                    .lines(4)
                    .subscribe(new Subscriber<String>() {
                        @Override
                        public void onSubscribe(Subscription s) {}

                        @Override
                        public void onNext(String item) {
                            assertEquals(iter.next(), item);
                        }

                        @Override
                        public void onError(Throwable throwable) {}

                        @Override
                        public void onComplete() {
                            latch.countDown();
                        }
                    });
            latch.await();
            assertFalse("Missing items not retrieved by lines subscriber!!", iter.hasNext());
        }finally {
            delete(Paths.get(PATH));
        }
    }


    @Test
    public void afwReadAllBytesTest() throws IOException {
        final String PATH = "output.txt";
        final List<String> expected = Arrays.asList("super", "brave", "isel", "ole", "gain", "massi", "tot");
        try (FileWriter writer = new FileWriter(PATH)) {
            expected.forEach(line -> write(writer, line));
        }
        try (AsyncFileReader reader = new AsyncFileReader(PATH)) {
            Iterator<String> iter = expected.iterator();
            reader
                    .readAllBytes(8)
                    .thenApply(bytes -> new String(bytes, UTF_8))
                    .thenApply(NEWLINE::splitAsStream)
                    .thenApply(Stream::iterator)
                    .thenAccept(actual -> expected.forEach(l -> {
                            if (actual.hasNext() == false) fail("File does not contain line: " + l);
                            assertEquals(l, actual.next());
                    }))
                    .join();
        }finally {
            delete(Paths.get(PATH));
        }
    }


    private static void write(FileWriter writer, String line) {
        try {
            writer.write(line);
            writer.write(lineSeparator());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}