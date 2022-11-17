package org.javasync.io.test;

import org.javaync.io.AsyncFiles;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static java.lang.ClassLoader.getSystemResource;
import static java.lang.System.out;
import static java.nio.file.Files.delete;
import static java.util.Arrays.asList;
import static org.testng.Assert.assertEquals;

@Test(singleThreaded=true)
public class ReadmeUsageTest {
    static final String OUTPUT = "output.txt";
    @Test
    public void readLinesAsyncForReadme() throws IOException {
        /**
         * Arrange
         */
        List<String> expected = asList("super", "brave", "isel", "ole", "gain", "massi", "tot");
        String path = OUTPUT;
        Files.write(Paths.get(path), expected);
        AsyncFiles
            .asyncQuery(path) // printing all lines from input.txt
            .onNext((line, err) -> out.println(line)) // lack check err
            .blockingSubscribe(); // block if you want to wait for completion
    }

    @Test
    public void readWriteTestAsyncForReadme() throws IOException, URISyntaxException {
        URL FILE = getSystemResource("Metamorphosis-by-Franz-Kafka.txt");
        Path in = Paths.get(FILE.toURI());
        try {
            AsyncFiles
                    .readAllBytes(in.toString())
                    .thenCompose(bytes -> AsyncFiles.writeBytes("output3.txt", bytes))
                    .join();
            byte[] expected = Files.readAllBytes(in);
            byte[] actual = Files.readAllBytes(Paths.get("output3.txt"));
            assertEquals(expected, actual);
        } finally {
            delete(Paths.get("output3.txt"));
        }
    }

    @Test
    public void readWriteTestSyncForReadme() throws IOException, URISyntaxException {
        URL FILE = getSystemResource("Metamorphosis-by-Franz-Kafka.txt");
        Path in = Paths.get(FILE.toURI());
        Path out = Paths.get("output4.txt");
        try {
            byte[] data = Files.readAllBytes(in);
            Files.write(out, data);
            byte[] expected = Files.readAllBytes(in);
            byte[] actual = Files.readAllBytes(out);
            assertEquals(expected, actual);
        } finally {
            delete(out);
        }
    }
}
