package org.javasync.io.test

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.javaync.io.AsyncFiles
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

@Test(singleThreaded = true)
class AsyncFileReaderFlowTest {

    @Test
    fun readLinesKotlinFlow() {
        val output = Paths.get("output.txt")
        try {
            val source = Arrays.asList("super", "brave", "isel", "ole", "gain", "massi", "tot")
            Files.write(output, source)
            val expected = source.iterator()
            runBlocking {
                AsyncFiles
                        .flow(output)
                        .collect { assertEquals(expected.next(), it) }
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
            AsyncFiles
                    .flow(file)
                    .collect { assertEquals(expected.next(), it) }
        }
        assertFalse(expected.hasNext(), "Missing items not retrieved by lines subscriber!!")
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