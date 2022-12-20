package org.javasync.io.test

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.javasync.io.test.AsyncFileReaderTest.NEWLINE
import org.javaync.io.*
import org.testng.Assert
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.annotations.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import java.util.Collections.max
import java.util.Comparator.comparingInt
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.Path
import kotlin.test.assertNotEquals

@Test(singleThreaded = true)
class AsyncFileReaderCoroutinesTest {

    @Test
    fun readFileTwiceWithInterleavedModification() {
        /**
         * Arrange
         */
        val init = listOf("super", "brave", "isel", "ole", "gain", "massi", "tot")
        val file = Path("output5.txt")
        Files.write(file, init)
        /**
         * Act and Assert
         */
        try {
            runBlocking {
                /**
                 * Assert
                 */
                with(init.iterator()) { // expected
                    file.readText().trim().lines().forEach {
                        kotlin.test.assertEquals(next(), it)
                    }
                }
                /**
                 * Act - Remove 1 line
                 */
                file.writeText((init - "isel").joinToString("\n"))
                /**
                 * Assert
                 */
                with((init - "isel").iterator()) { // expected
                    file.readText().trim().lines().forEach {
                        kotlin.test.assertEquals(next(), it)
                    }
                }
            }
        } finally {
            Files.delete(file)
        }
    }


    @Test
    fun readLinesKotlinFlow() {
        val output = Paths.get("output.txt")
        try {
            val source = Arrays.asList("super", "brave", "isel", "ole", "gain", "massi", "tot")
            Files.write(output, source)
            val expected = source.iterator()
            runBlocking {
                output
                        .lines()
                        .collect {
                            assertEquals(expected.next(), it)
                        }
            }
            assertFalse(expected.hasNext())
        } finally { Files.delete(output) }
    }

    @Test
    fun readLinesFromLargeFile() {
        /**
         * Arrange
         */
        val file = Paths.get(AsyncFileReaderTest.WIZARD.toURI())
        val expected = Files
                .lines(file, StandardCharsets.UTF_8)
                .iterator()
        /**
         * Act and Assert
         */
        runBlocking {
            var count = 0
            file
                    .lines()
                    .collect { assertEquals(expected.next(), it) }
        }
        assertFalse(expected.hasNext(), "Missing items not retrieved by lines subscriber!!")
    }

     @Test
    fun readLinesFromLargeFileWithCoroutines() {
        /**
         * Arrange
         */
        val file = Paths.get(AsyncFileReaderTest.WIZARD.toURI())
        val expected = Files
                .lines(file, StandardCharsets.UTF_8)
                .iterator()
        /**
         * Act and Assert
         */
        runBlocking {
            readAll(file.toString()).let { NEWLINE.splitAsStream(it).forEach { actual ->
                assertEquals(expected.next(), actual)
            }}

        }
        assertFalse(expected.hasNext(), "Missing items not retrieved by lines subscriber!!")
    }

    @Test
    fun writeBytesTest() {
        val OUTPUT = "output77.txt"
        val FILE = ClassLoader.getSystemResource("Metamorphosis-by-Franz-Kafka.txt")
        val PATH = Paths.get(FILE.toURI())
        val expected = Files.readAllBytes(PATH)
        runBlocking {
            val index: Int = writeText(OUTPUT, expected.decodeToString())
            assertNotEquals(0, index)
        }
        try {
            AsyncFiles
                .readAllBytes(Paths.get(OUTPUT))
                .whenComplete { actual: ByteArray?, ex: Throwable? ->
                    if (ex != null) Assert.fail(ex.message)
                    assertEquals(expected, actual)
                }
                .join()
        } finally {
            Files.delete(Paths.get(OUTPUT))
        }
    }

    @Test
    fun readLinesFromLargeFileAndCountWords() {
        val MIN = 5
        val MAX = 10
        val file = Paths.get(AsyncFileReaderTest.WIZARD.toURI())
        val words = ConcurrentHashMap<String, Int>()
        runBlocking {
            AsyncFiles
                .flow(file)
                .filter { !it.isEmpty() }                   // Skip empty lines
                .drop(14)                                   // Skip gutenberg header
                .takeWhile { !it.contains("*** END OF ") }  // Skip gutenberg footnote
                .flatMapMerge { it.splitToSequence(" ").asFlow() }
                .filter{ it.length in (MIN + 1) until MAX }
                .collect{ words.merge(it, 1, Integer::sum) }
        }
        val common = max(words.entries, comparingInt { e -> e.value })
        assertEquals("Hokosa", common.key)
        assertEquals(183, common.value)
    }
}
