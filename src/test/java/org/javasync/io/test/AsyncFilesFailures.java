/*
 * MIT License
 *
 * Copyright (c) 2019, Miguel Gamboa (gamboa.pt)
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
 */

package org.javasync.io.test;

import org.javasync.util.Subscribers;
import org.javaync.io.AsyncFiles;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.channels.NonWritableChannelException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

import static java.lang.ClassLoader.getSystemResource;
import static java.nio.channels.AsynchronousFileChannel.open;
import static java.nio.file.Files.delete;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.util.Arrays.asList;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

@Test(singleThreaded=true)
public class AsyncFilesFailures {
    static final URL METAMORPHOSIS = getSystemResource("Metamorphosis-by-Franz-Kafka.txt");
    static final URL UTF_8_INVALID = getSystemResource("UTF-8-test.txt");

    @Test
    public void readAllNoSuchFile() {
        AsyncFiles
            .readAllBytes(Paths.get("non-existent-file.sgf"))
            .whenComplete((pos, err) -> {
                    if(err == null)
                        fail("It should fail reading an nonexistent!");
                    else
                        assertEquals(err.getClass(), NoSuchFileException.class);
                });
    }

    @Test
    public void readAllLinesNoSuchFile() {
        AsyncFiles
            .lines("non-existent-file.sgf")
            .subscribe(Subscribers
                        .doOnNext(line ->
                            fail("It should fail reading an nonexistent!")
                        )
                        .doOnError(err ->
                            assertEquals(err.getClass(), NoSuchFileException.class)
                        ));
    }

    @Test
    public void readAllToCallbackNoSuchFile() {
        AsyncFiles
            .readAll("non-existent-file.sgf", (err, data) -> {
                    if(err == null || data != null)
                        fail("It should fail reading an nonexistent!");
                    else
                        assertEquals(err.getClass(), NoSuchFileException.class);
            });
    }


    @Test
    public void concurrentReadLines() throws IOException {
        /**
         * Arrange
         */
        final Path OUTPUT = Paths.get("dummy1.txt");
        List<String> expected = asList("super", "brave", "isel", "ole", "gain", "massi", "tot");
        Files.write(OUTPUT, expected, CREATE);
        /**
         * Act and Assert
         */
        try{
            CompletableFuture<Void> promise = new CompletableFuture<>();
            boolean [] done = {false};
            AsyncFiles
                .lines(8, OUTPUT)
                .subscribe(Subscribers
                        .doOnNext(item -> {
                            if(!done[0]) {
                                openLock(OUTPUT, CREATE);
                                done[0] = true;
                            }
                        })
                        .doOnSubscribe(sign -> sign.request(Integer.MAX_VALUE))
                        .doOnError(err -> {

                            assertTrue(err instanceof NonWritableChannelException);
                            promise.complete(null);
                        }));
            promise.join();
        }
        finally {
            Files.delete(OUTPUT);
        }
    }

    @Test
    public void concurrentReadBytes() throws IOException, ExecutionException, InterruptedException, URISyntaxException {
        Path path = Paths.get(METAMORPHOSIS.toURI());
        CompletableFuture<byte[]> p = AsyncFiles
            .readAllBytes(path, 8)
            .whenComplete((arr, err) -> {
                assertNull(arr);
                assertTrue(err instanceof CompletionException);
            });
        openLock(path, WRITE);
        try{
            p.join();
            fail("It should be completed exceptionally!");
        } catch(CompletionException e) {
        }
    }

    public void concurrentWriteLines() throws IOException, URISyntaxException {
        final Path OUTPUT = Paths.get("dummy2.txt");
        Stream<String> lines = Files.lines(Paths.get(METAMORPHOSIS.toURI()));
        Iterable<String> expected = () -> lines.iterator();
        try {
            Files.createFile(OUTPUT);
            openLock(OUTPUT, WRITE);
            CompletableFuture<Integer> p = AsyncFiles.write(OUTPUT, expected);
            p.join();
            fail("It should be completed exceptionally!");
        } catch (CompletionException e) {
            /* Assert true */
        }
        finally {
            delete(OUTPUT);
        }
    }

    @Test
    public void concurrentBytesLinesOnOpen() throws IOException {
        final Path OUTPUT = Paths.get("dummy3.txt");
        Files.write(OUTPUT, "ola".getBytes(), CREATE);
        try {
            AsyncFiles
                .writeBytes(OUTPUT, null)
                .whenComplete((pos, err) -> {
                    if(err == null)
                        fail("It should fail creating a file that already exists");
                    else
                        assertEquals(err.getClass(), IOException.class);
                });
        }
        finally {
            delete(OUTPUT);
        }
    }

    @Test
    public void concurrentWriteLinesOnOpen() throws IOException {
        final Path OUTPUT = Paths.get("dummy4.txt");
        Files.write(OUTPUT, "ola".getBytes(), CREATE);
        try {
            AsyncFiles
                .write(OUTPUT, null)
                .whenComplete((pos, err) -> {
                    if(err == null)
                        fail("It should fail creating a file that already exists");
                    else
                        assertEquals(err.getClass(), IOException.class);
                });
        }
        finally {
            delete(OUTPUT);
        }
    }

    /**
     * From release 1.1.4 there is no more error reading invalid characters.
     * Keeping this test for an eventual future issue.
     */
    // @Test(expected = CompletionException.class)
    @Test
    public void readLinesWithInvalidBytes() throws URISyntaxException {
        Path PATH = Paths.get(UTF_8_INVALID.toURI());
        CompletableFuture<Void> p = new CompletableFuture<>();
        AsyncFiles
                .lines(4, PATH)
                .subscribe(Subscribers
                        .doOnNext((item) -> {})
                        .doOnSubscribe(sign -> sign.request(Integer.MAX_VALUE))
                        .doOnError(err -> fail())
                        .doOnComplete(() ->
                            p.complete(null)
                        )
                );
        p.join();
    }

    private static void openLock(Path output, StandardOpenOption opt) {
        try {
            open(output, opt).lock().get();
        } catch (InterruptedException | IOException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }
}
