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

import junit.framework.AssertionFailedError;
import org.javaync.io.AsyncFiles;
import org.junit.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.lang.ClassLoader.getSystemResource;
import static java.lang.System.lineSeparator;
import static java.nio.file.Files.delete;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.joining;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

public class AsyncFileReaderTest {

    static final Pattern NEWLINE = Pattern.compile("\n");

    @Test
    public void afwReadLinesWithReactorTest() throws IOException, InterruptedException {
        /**
         * Arrange
         */
        String PATH = "output.txt";
        List<String> expected = asList("super", "brave", "isel", "ole", "gain", "massi", "tot");
        Files.write(Paths.get(PATH), expected);
        try {
            /**
             * Act and Assert
             */
            Iterator<String> iter = expected.iterator();
            Flux
                    .from(AsyncFiles.lines(8, PATH)) // Act
                    .doOnNext(line -> assertEquals(iter.next() + "\r", line)) // Assert
                    .blockLast();
            assertFalse("Missing items retrieved by lines subscriber!!", iter.hasNext());
        } finally {
            delete(Paths.get(PATH));
        }
    }

    @Test
    public void afwReadLinesTest() throws IOException, InterruptedException {
        final String PATH = "output.txt";
        final List<String> expected = asList("super", "brave", "isel", "ole", "gain", "massi", "tot");
        Files.write(Paths.get(PATH), expected);
        try {
            CompletableFuture<Void> p = new CompletableFuture<>();
            Iterator<String> iter = expected.iterator();
            AsyncFiles
                    .lines(4, PATH)
                    .subscribe(new Subscriber<String>() {
                        public void onSubscribe(Subscription s) { }
                        public void onNext(String item) {
                            String curr = iter.next() + "\r";
                            if(!curr.equals(item)) {
                                String msg = curr + " != " + item;
                                p.completeExceptionally(new AssertionFailedError(msg));
                            }
                        }
                        public void onError(Throwable throwable) { }
                        public void onComplete() {
                            p.complete(null);
                        }
                    });
            p.join();
            assertFalse("Missing items not retrieved by lines subscriber!!", iter.hasNext());
        } finally {
            delete(Paths.get(PATH));
        }
    }


    @Test
    public void afwReadAllBytesTest() throws IOException {
        final String PATH = "output.txt";
        final List<String> expected = asList("super", "brave", "isel", "ole", "gain", "massi", "tot");
        Files.write(Paths.get(PATH), expected);
        try {
            Iterator<String> iter = expected.iterator();
            AsyncFiles
                    .readAll(PATH, 8)
                    .thenApply(NEWLINE::splitAsStream)
                    .thenApply(Stream::iterator)
                    .thenAccept(actual -> expected.forEach(l -> {
                            if (actual.hasNext() == false) fail("File does not contain line: " + l);
                            assertEquals(l + "\r", actual.next());
                    }))
                    .join();
        }finally {
            delete(Paths.get(PATH));
        }
    }

    @Test
    public void ReadAllBytesLargeTextFileTest() throws IOException, URISyntaxException {
        /**
         * Arrange
         */
        URL FILE = getSystemResource("Metamorphosis-by-Franz-Kafka.txt");
        Path PATH = Paths.get(FILE.toURI());
        Iterator<String> expected = Files
                .lines(PATH, StandardCharsets.UTF_8)
                .iterator();
        /**
         * Act and Assert
         */
        AsyncFiles
                .readAll(PATH)
                .thenAccept(actual -> NEWLINE
                        .splitAsStream(actual)
                        .forEach(line -> {
                            if(!expected.hasNext())
                                fail("More items read than expected!");
                            assertEquals(expected.next() + "\r", line);
                        }))
                .join();
        if(expected.hasNext())
            fail("There are missing lines to read: " + expected.next());

    }

    @Test
    public void ReadAllLargeTextFileTest() throws IOException, URISyntaxException {
        /**
         * Arrange
         */
        URL FILE = getSystemResource("Metamorphosis-by-Franz-Kafka.txt");
        Path PATH = Paths.get(FILE.toURI());
        String expected = Files
                .lines(PATH, StandardCharsets.UTF_8)
                .map(line -> line + lineSeparator())
                .collect(joining());
        /**
         * Act and Assert
         */
        AsyncFiles
                .readAll(PATH.toString())
                .thenAccept(actual -> assertEquals(expected, actual))
                .join();
    }

    @Test
    public void ReadAllViaCallbackLargeTextFileTest() throws IOException, URISyntaxException {
        /**
         * Arrange
         */
        URL FILE = getSystemResource("Metamorphosis-by-Franz-Kafka.txt");
        Path PATH = Paths.get(FILE.toURI());
        String expected = Files
                .lines(PATH, StandardCharsets.UTF_8)
                .map(line -> line + lineSeparator())
                .collect(joining());
        /**
         * Act and Assert
         */
        CompletableFuture<Void> p = new CompletableFuture<>();
        AsyncFiles
                .readAll(PATH.toString(), (err, actual) -> {
                    assertEquals(expected, actual);
                    p.complete(null);
                });
        p.join();
    }

    @Test
    public void ReadAllLinesLargeTextFileWithReactorTest() throws IOException, URISyntaxException {
        /**
         * Arrange
         */
        URL FILE = getSystemResource("Metamorphosis-by-Franz-Kafka.txt");
        Path PATH = Paths.get(FILE.toURI());
        Iterator<String> expected = Files
                .lines(PATH, StandardCharsets.UTF_8)
                .iterator();
        /**
         * Act and Assert
         */
        Flux
                .from(AsyncFiles.lines(PATH.toString())) // Act
                .doOnError(ex -> fail(ex.getMessage()))
                .doOnNext(line -> {
                    if(!expected.hasNext())
                        fail("More items read than expected!");
                    String next = expected.next();
                    assertEquals(next + "\r", line);
                })
                .blockLast();
        if(expected.hasNext())
            fail("There are missing lines to read: " + expected.next());
    }
}