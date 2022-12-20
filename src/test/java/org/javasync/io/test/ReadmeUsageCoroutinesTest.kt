package org.javasync.io.test

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.javaync.io.lines
import org.javaync.io.readText
import org.javaync.io.writeText
import org.testng.Assert
import org.testng.annotations.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.Path
import kotlin.io.path.readLines

@Test(singleThreaded = true)
class ReadmeUsageCoroutinesTest {

    private suspend fun copyNio(from: String, to: String) {
        val data = Path(from).readText() // suspension point
        Path(to).writeText(data)         // suspension point
    }

    private fun copy(from: String, to: String) {
        val data = File(from).readText()
        File(to).writeText(data)
    }

    @Test
    fun readWriteCoroutinesAsyncForReadme() {
        val FILE = ClassLoader.getSystemResource("Metamorphosis-by-Franz-Kafka.txt")
        val source = Paths.get(FILE.toURI())
        try {
            runBlocking {
                copyNio(source.toString(), "output3.txt")
            }
            val expected = Files.readAllBytes(source)
            val actual = Files.readAllBytes(Paths.get("output3.txt"))
            Assert.assertEquals(expected, actual)
        } finally {
            Files.delete(Paths.get("output3.txt"))
        }
    }

    @Test
    fun readWriteTestSyncForReadme() {
        val FILE = ClassLoader.getSystemResource("Metamorphosis-by-Franz-Kafka.txt")
        val source = Paths.get(FILE.toURI())
        try {
            copy(source.toString(), "output4.txt")
            val expected = Files.readAllBytes(source)
            val actual = Files.readAllBytes(Path("output4.txt"))
            Assert.assertEquals(expected, actual)
        } finally {
            Files.delete(Path("output4.txt"))
        }
    }

    private fun readLinesSyncForReadme() {
        Path("input.txt")
            .readLines() // List<String>
            .forEach(::println)
    }

    private suspend fun readLinesFlowForReadme() {
        Path("input.txt")
            .lines()   // Flow<String>
            .onEach(::println)
            .collect() // block if you want to wait for completion
    }
}
