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

import org.javasync.util.Subscribers;
import org.javaync.io.AsyncFiles;
import org.reactivestreams.Subscription;
import org.testng.Assert;
import org.testng.annotations.Test;
import reactor.core.publisher.Flux;

import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.lang.ClassLoader.getSystemResource;
import static java.lang.System.lineSeparator;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.delete;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.joining;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

@Test(singleThreaded=true)
public class AsyncFileReaderTest {

    static final Pattern NEWLINE = Pattern.compile("(\r\n|\n|\r)");
    static final String OUTPUT = "output.txt";
    static final URL METAMORPHOSIS = getSystemResource("Metamorphosis-by-Franz-Kafka.txt");
    static final URL WIZARD = getSystemResource("The-Wizard-by-Rider-Haggard.txt");
    static final URL SMALL = getSystemResource("Small.txt");

    @Test
    public void readLinesWith8BytesBufferToReactorFlux() throws IOException {
        /**
         * Arrange
         */
        List<String> expected = asList("super", "brave", "isel", "ole", "gain", "massi", "tot");
        Files.write(Paths.get(OUTPUT), expected);
        try {
            /**
             * Act and Assert
             */
            Iterator<String> iter = expected.iterator();
            Flux
                    .from(AsyncFiles.lines(8, OUTPUT)) // Act
                    .doOnNext(line -> Assert.assertEquals(line, iter.next())) // Assert
                    .blockLast();
            assertFalse(iter.hasNext(), "Missing items retrieved by lines subscriber!!");
        } finally {
            delete(Paths.get(OUTPUT));
        }
    }

    @Test
    public void readLinesWith4BytesBuffer() throws IOException {
        final List<String> expected = asList("super", "brave", "isel", "ole", "gain", "massi", "tot");

        /*
         * Deliberately using a FileWriter rather than Files.write()
         * to put force LF instead of CRLF.
         */
        try(FileWriter out = new FileWriter(OUTPUT)) {
            expected.forEach(item -> writeLine(out, item));
            out.flush();
        }
        try {
            CompletableFuture<Void> p = new CompletableFuture<>();
            Iterator<String> iter = expected.iterator();
            AsyncFiles
                    .lines(4, OUTPUT)
                    .subscribe(Subscribers
                        .doOnNext(item -> {
                            if(p.isDone()) return;
                            String curr = iter.next();
                            assertEquals(curr, item);
                        })
                        .doOnSubscribe(sign -> sign.request(Integer.MAX_VALUE))
                        .doOnError(err -> p.completeExceptionally(err))
                        .doOnComplete(() -> p.complete(null)));
            p.join();
            assertFalse(iter.hasNext(), "Missing items not retrieved by lines subscriber!!");
        } finally {
            delete(Paths.get(OUTPUT));
        }
    }

    /**
     * Auxiliary method to force put a LF instead of CRLF.
     */
    private void writeLine(FileWriter out, String item) {
        try {
            out.write(item + '\n');
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    public void readAllBytesWith8BytesBuffer() throws IOException {
        final List<String> expected = asList("super", "brave", "isel", "ole", "gain", "massi", "tot");
        Files.write(Paths.get(OUTPUT), expected);
        try {
            Iterator<String> iter = expected.iterator();
            AsyncFiles
                    .readAll(OUTPUT, 8)
                    .thenApply(NEWLINE::splitAsStream)
                    .thenApply(Stream::iterator)
                    .thenAccept(actual -> expected.forEach(l -> {
                            if (actual.hasNext() == false) fail("File does not contain line: " + l);
                            String curr = actual.next();
                            assertEquals(l, curr);
                    }))
                    .join();
        }finally {
            delete(Paths.get(OUTPUT));
        }
    }

    @Test
    public void readAllBytesFromLargeFile() throws IOException, URISyntaxException {
        /**
         * Arrange
         */
        Path PATH = Paths.get(METAMORPHOSIS.toURI());
        Iterator<String> expected = Files
                .lines(PATH, UTF_8)
                .iterator();
        /**
         * Act and Assert
         */
        AsyncFiles
                .readAll(PATH.toString()) // KEEP like this with toString() to force invocation chain cover more methods.
                .thenAccept(actual -> NEWLINE
                        .splitAsStream(actual)
                        .forEach(line -> {
                            if(!expected.hasNext())
                                fail("More items read than expected!");
                            assertEquals(expected.next(), line);
                        }))
                .join();
        if(expected.hasNext())
            fail("There are missing lines to read: " + expected.next());

    }

    @Test
    public void readLinesFromLargeFile() throws IOException, URISyntaxException {
        /**
         * Arrange
         */
        Path PATH = Paths.get(WIZARD.toURI());
        Iterator<String> expected = Files
            .lines(PATH, UTF_8)
            .iterator();
        /**
         * Act and Assert
         */
        CompletableFuture<Void> completed = new CompletableFuture<>();
        AsyncFiles
                .lines(PATH.toString())
                .subscribe(Subscribers
                        .doOnNext(item -> {
                            if(completed.isDone()) return;
                            String curr = expected.next();
                            assertEquals(curr, item);
                        })
                        .doOnSubscribe(sign -> sign.request(Integer.MAX_VALUE))
                        .doOnError(err -> completed.completeExceptionally(err))
                        .doOnComplete(() -> completed.complete(null)));
            completed.join();
            assertFalse(expected.hasNext(), "Missing items not retrieved by lines subscriber!!");
    }

    @Test
    public void readLinesFromLargeFileAndSleep() throws IOException, URISyntaxException, InterruptedException {
        /**
         * Arrange
         */
        Path PATH = Paths.get(WIZARD.toURI());
        Iterator<String> expected = Files
            .lines(PATH, UTF_8)
            .iterator();
        /**
         * Act and Assert
         */
        CompletableFuture<Void> completed = new CompletableFuture<>();
        CompletableFuture<Subscription> sign = new CompletableFuture<>();
        AsyncFiles
                .lines(PATH.toString())
                .subscribe(Subscribers
                        .doOnNext(item -> {
                            if(completed.isDone()) return;
                            String curr = expected.next();
                            assertEquals(curr, item);
                        })
                        .doOnSubscribe(s -> {
                            s.request(400);
                            sign.complete(s);
                        })
                        .doOnError(err -> completed.completeExceptionally(err))
                        .doOnComplete(() -> completed.complete(null)));
        Thread.sleep(1000);
        sign.join().request(Long.MAX_VALUE);
        completed.join();
        assertFalse(expected.hasNext(), "Missing items not retrieved by lines subscriber!!");
    }

    @Test
    public void readLinesFromLargeFileAndCancelation() throws URISyntaxException, InterruptedException {
        CompletableFuture<Void> completed = new CompletableFuture<>();
        CompletableFuture<Subscription> subscribed = new CompletableFuture<>();
        int [] count = {0};
        AsyncFiles
                .lines(64, Paths.get(WIZARD.toURI()))
                .subscribe(Subscribers
                        .doOnNext(item -> {
                            count[0]++;
                            if(count[0] > 400){
                                subscribed.join().cancel();
                                completed.complete(null);
                            }
                        })
                        .doOnSubscribe(s -> {
                            s.request(Integer.MAX_VALUE);
                            subscribed.complete(s);
                        })
                        .doOnError(err -> completed.completeExceptionally(err)));
        completed.join();
        Thread.sleep(500); // Wait a little to check that no further signals were emitted in the meanwhile.
        assertEquals(401, count[0]);
    }

    @Test
    public void readLinesFromLargeFileRequestRandomly() throws IOException, URISyntaxException {
        /**
         * Arrange
         */
        Path PATH = Paths.get(WIZARD.toURI());
        Iterator<String> expected = Files
            .lines(PATH, UTF_8)
            .iterator();
        /**
         * Act and Assert
         */
        Random rand = new Random();
        CompletableFuture<Void> completed = new CompletableFuture<>();
        Subscription[] sign = { null };
        AtomicInteger signals = new AtomicInteger();
        AtomicInteger prevRequest = new AtomicInteger();
        AsyncFiles
                .lines(PATH.toString())
                .subscribe(Subscribers
                        .doOnNext(item -> {
                            if(completed.isDone()) return;
                            String curr = expected.next();
                            assertEquals(curr, item);
                            if(prevRequest.decrementAndGet() == 0) {
                                int req = rand.nextInt(50) + 1;
                                sign[0].request(req);
                                prevRequest.addAndGet(req);
                                signals.incrementAndGet();
                            }
                        })
                        .doOnSubscribe(s -> {
                            int req = rand.nextInt(50) + 1;
                            s.request(req);
                            prevRequest.addAndGet(req);
                            sign[0] = s;
                        })
                        .doOnError(err -> completed.completeExceptionally(err))
                        .doOnComplete(() -> completed.complete(null)));
        completed.join();
        assertFalse(expected.hasNext(), "Missing items not retrieved by lines subscriber!!");
        assertTrue(signals.get() < 2000);
    }

    @Test
    public void readAllBytesFromLargeFileViaCallbackConcurrently() throws IOException, URISyntaxException {
        /**
         * Arrange
         */
        Path PATH = Paths.get(METAMORPHOSIS.toURI());
        String expected = Files
                .lines(PATH, UTF_8)
                .map(line -> line + lineSeparator())
                .collect(joining());
        /**
         * Act and Assert
         */
        CompletableFuture<Void> p1 = new CompletableFuture<>();
        CompletableFuture<Void> p2 = new CompletableFuture<>();
        AsyncFiles
                .readAll(PATH.toString(), (err, actual) -> {
                    assertEquals(expected, actual);
                    p1.complete(null);
                });
        AsyncFiles
                .readAll(PATH.toString(), (err, actual) -> {
                    assertEquals(expected, actual);
                    p2.complete(null);
                });
        p1.join();
        p2.join();
    }

    @Test
    public void readLinesFromLargeFileWith8BytesBufferToReactorFlux() throws IOException, URISyntaxException {
        /**
         * Arrange
         */
        Path PATH = Paths.get(METAMORPHOSIS.toURI());
        Iterator<String> expected = Files
                .lines(PATH, UTF_8)
                .iterator();
        /**
         * Act and Assert
         */
        Flux
                .from(AsyncFiles.lines(8, PATH.toString())) // Act
                .doOnError(ex -> fail(ex.getMessage()))
                .doOnNext(line -> {
                    if(!expected.hasNext())
                        fail("More items read than expected!");
                    String next = expected.next();
                    assertEquals(next, line);
                })
                .blockLast();
        if(expected.hasNext())
            fail("There are missing lines to read: " + expected.next());
    }
}