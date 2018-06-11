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
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.lang.ClassLoader.getSystemResource;
import static java.lang.System.*;
import static java.nio.file.Files.delete;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.joining;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

public class AsyncFileReaderTest {

    static final Pattern NEWLINE = Pattern.compile("\n");

    @Test
    public void readLinesWithReactorTest() throws IOException, InterruptedException {
        /**
         * Arrange
         */
        String PATH = "output.txt";
        List<String> expected = asList("super", "brave", "isel", "ole", "gain", "massi", "tot");
        writeLinesSync(PATH, expected);
        try {
            /**
             * Act and Assert
             */
            Iterator<String> iter = expected.iterator();
            Flux
                    .from(AsyncFileReader.lines(8, PATH)) // Act
                    .doOnNext(line -> assertEquals(iter.next(), line)) // Assert
                    .blockLast();
            assertFalse("Missing items retrieved by lines subscriber!!", iter.hasNext());
        } finally {
            delete(Paths.get(PATH));
        }
    }

    @Test
    public void readLinesTest() throws IOException, InterruptedException {
        final String PATH = "output.txt";
        final List<String> expected = asList("super", "brave", "isel", "ole", "gain", "massi", "tot");
        writeLinesSync(PATH, expected);
        try {
            final CountDownLatch latch = new CountDownLatch(1);
            Iterator<String> iter = expected.iterator();
            AsyncFileReader
                    .lines(4, PATH)
                    .subscribe(new Subscriber<String>() {
                        @Override
                        public void onSubscribe(Subscription s) {
                        }

                        @Override
                        public void onNext(String item) {
                            assertEquals(iter.next(), item);
                        }

                        @Override
                        public void onError(Throwable throwable) {
                        }

                        @Override
                        public void onComplete() {
                            latch.countDown();
                        }
                    });
            latch.await();
            assertFalse("Missing items not retrieved by lines subscriber!!", iter.hasNext());
        } finally {
            delete(Paths.get(PATH));
        }
    }


    @Test
    public void readAllBytesTest() throws IOException {
        final String PATH = "output.txt";
        final List<String> expected = asList("super", "brave", "isel", "ole", "gain", "massi", "tot");
        writeLinesSync(PATH, expected);
        try {
            Iterator<String> iter = expected.iterator();
            AsyncFileReader
                    .readAll(PATH, 8)
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

    @Test
    public void readAllBytesLargeTextFileTest() throws IOException, URISyntaxException {
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
        AsyncFileReader
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
    public void readAllLargeTextFileTest() throws IOException, URISyntaxException {
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
        AsyncFileReader
                .readAll(PATH.toString())
                .thenAccept(actual -> assertEquals(expected, actual))
                .join();
    }

    @Test
    public void readAllLinesLargeTextFileWithReactorTest() throws IOException, URISyntaxException {
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
                .from(AsyncFileReader.lines(PATH.toString())) // Act
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

    private static void writeLinesSync(String path, List<String> lines) throws IOException {
        try (FileWriter writer = new FileWriter(path)) {
            String data = lines.stream().collect(joining("\n"));
            writer.write(data);
        }
    }
}