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

import org.javaync.io.AsyncFiles;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import static java.lang.ClassLoader.getSystemResource;
import static java.nio.file.Files.delete;
import static java.nio.file.Files.lines;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

@Test(singleThreaded=true)
public class AsyncFileWriterTest {

    @Test
    public void writeLinesTest() throws IOException {
        final String PATH = "output1.txt";
        final List<String> expected = Arrays.asList("super", "brave", "isel", "ole", "gain", "massive");
        try {
            AsyncFiles
                    .write(PATH, expected)
                    .whenComplete((index, ex) -> {
                        if (ex != null) fail(ex.getMessage());
                    })
                    .join();
            Iterator<String> actual = lines(Paths.get(PATH)).iterator();
            if (actual.hasNext() == false)
                fail("File is empty!!!");
            expected.forEach(l -> {
                if (actual.hasNext()) assertEquals(l, actual.next());
                else fail("File does not contain line: " + l);
            });
        } finally {
            delete(Paths.get(PATH));
        }
    }

    @Test
    public void writeBytesTest() throws IOException, URISyntaxException {
        final String OUTPUT = "output2.txt";
        URL FILE = getSystemResource("Metamorphosis-by-Franz-Kafka.txt");
        Path PATH = Paths.get(FILE.toURI());
        byte[] expected = Files.readAllBytes(PATH);
        AsyncFiles
                .writeBytes(Paths.get(OUTPUT), expected)
                .join();
        try {
            AsyncFiles
                    .readAllBytes(Paths.get(OUTPUT))
                    .whenComplete((actual, ex) -> {
                        if (ex != null) fail(ex.getMessage());
                        assertEquals(expected, actual);
                    })
                    .join();
        }finally {
            delete(Paths.get(OUTPUT));
        }
    }
}
