package org.example

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.example.lib.FileEvent
import org.example.lib.SearchResult
import org.example.lib.tokenizers.Tokenizer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@OptIn(ExperimentalCoroutinesApi::class)
class FileIndexerTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var testFile1: Path
    private lateinit var testFile2: Path
    private lateinit var testSubDir: Path
    private lateinit var testFile3: Path
    private lateinit var nonTxtFile: Path

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @BeforeEach
    fun setUp() {
        testFile1 = tempDir.resolve("test1.txt")
        testFile2 = tempDir.resolve("test2.txt")
        testSubDir = tempDir.resolve("subdir")
        Files.createDirectory(testSubDir)
        testFile3 = testSubDir.resolve("test3.txt")
        nonTxtFile = tempDir.resolve("test.json")

        testFile1.writeText("hello world kotlin programming")
        testFile2.writeText("world java programming language")
        testFile3.writeText("kotlin coroutines async programming")
        nonTxtFile.writeText("json data structure")
    }

    @AfterEach
    fun tearDown() {
        testScope.cancel()
    }

    @Test
    fun `builder should create FileIndexer with default values`() {
        val fileIndexer =
            FileIndexer.builder()
                .addPath(tempDir.toString())
                .build()

        assertNotNull(fileIndexer)
    }

    @Test
    fun `builder should allow adding single path`() {
        val fileIndexer =
            FileIndexer.builder()
                .addPath(tempDir.toString())
                .build()

        assertNotNull(fileIndexer)
    }

    @Test
    fun `builder should allow adding multiple paths with varargs`() {
        val fileIndexer =
            FileIndexer.builder()
                .addPaths(tempDir.toString(), testSubDir.toString())
                .build()

        assertNotNull(fileIndexer)
    }

    @Test
    fun `builder should allow adding multiple paths with collection`() {
        val paths = listOf(tempDir.toString(), testSubDir.toString())
        val fileIndexer =
            FileIndexer.builder()
                .addPaths(paths)
                .build()

        assertNotNull(fileIndexer)
    }

    @Test
    fun `builder should allow custom tokenizer`() {
        val mockTokenizer = mock<Tokenizer>()
        val fileIndexer =
            FileIndexer.builder()
                .addPath(tempDir.toString())
                .tokenizer(mockTokenizer)
                .build()

        assertNotNull(fileIndexer)
    }

    @Test
    fun `builder should allow custom file filter`() {
        val customFilter: (String) -> Boolean = { it.endsWith(".kt") }
        val fileIndexer =
            FileIndexer.builder()
                .addPath(tempDir.toString())
                .fileFilter(customFilter)
                .build()

        assertNotNull(fileIndexer)
    }

    @Test
    fun `start should index files in directory`() =
        runTest {
            val fileIndexer =
                FileIndexer.builder()
                    .addPath(tempDir.toString())
                    .build()

            fileIndexer.start()

            try {
                val results = fileIndexer.search("hello")
                assertEquals(1, results.size)
                assertTrue(results[0].file.contains("test1.txt"))

                val worldResults = fileIndexer.search("world")
                assertEquals(2, worldResults.size)
            } finally {
                fileIndexer.close()
            }
        }

    @Test
    fun `start should index single file`() =
        runTest {
            val fileIndexer =
                FileIndexer.builder()
                    .addPath(testFile1.toString())
                    .build()

            fileIndexer.start()

            try {
                val results = fileIndexer.search("hello")
                assertEquals(1, results.size)
                assertTrue(results[0].file.contains("test1.txt"))
            } finally {
                fileIndexer.close()
            }
        }

    @Test
    fun `search should return empty list for non-existent word`() =
        runTest {
            val fileIndexer =
                FileIndexer.builder()
                    .addPath(tempDir.toString())
                    .build()

            fileIndexer.start()

            try {
                val results = fileIndexer.search("nonexistent")
                assertTrue(results.isEmpty())
            } finally {
                fileIndexer.close()
            }
        }

    @Test
    fun `search should handle empty word`() =
        runTest {
            val fileIndexer =
                FileIndexer.builder()
                    .addPath(tempDir.toString())
                    .build()

            fileIndexer.start()

            try {
                val results = fileIndexer.search("")
                assertTrue(results.isEmpty())
            } finally {
                fileIndexer.close()
            }
        }

    @Test
    fun `search with multiple words should return ranked results`() =
        runTest {
            val fileIndexer =
                FileIndexer.builder()
                    .addPath(tempDir.toString())
                    .build()

            fileIndexer.start()

            try {
                val results = fileIndexer.search(listOf("programming", "kotlin"))
                assertTrue(results.isNotEmpty())

                if (results.size > 1) {
                    assertTrue(results[0].matches.size >= results[1].matches.size)
                }
            } finally {
                fileIndexer.close()
            }
        }

    @Test
    fun `search with empty word collection should return empty list`() =
        runTest {
            val fileIndexer =
                FileIndexer.builder()
                    .addPath(tempDir.toString())
                    .build()

            fileIndexer.start()

            try {
                val results = fileIndexer.search(emptyList<String>())
                assertTrue(results.isEmpty())
            } finally {
                fileIndexer.close()
            }
        }

    @Test
    fun `file filter should exclude non-matching files`() =
        runTest {
            val fileIndexer =
                FileIndexer.builder()
                    .addPath(tempDir.toString())
                    .fileFilter { it.endsWith(".txt") }
                    .build()

            fileIndexer.start()

            try {
                val results = fileIndexer.search("json")
                assertTrue(results.isEmpty())

                val txtResults = fileIndexer.search("hello")
                assertEquals(1, txtResults.size)
            } finally {
                fileIndexer.close()
            }
        }

    @Test
    fun `watchForChanges should emit file events`() =
        runTest {
            val fileIndexer =
                FileIndexer.builder()
                    .addPath(tempDir.toString())
                    .build()

            fileIndexer.start()

            try {
                val events = mutableListOf<FileEvent>()
                val job =
                    launch {
                        fileIndexer.watchForChanges().take(1).toList(events)
                    }

                withContext(Dispatchers.IO) {
                    delay(500) // Give more time for watcher to set up

                    // Create a new file
                    val newFile = tempDir.resolve("new.txt")
                    newFile.writeText("new content")

                    // Force file system sync
                    Files.write(newFile, "new content".toByteArray())
                }

                // Wait for the event with real time
                withContext(Dispatchers.Default.limitedParallelism(1)) {
                    withTimeout(3000) {
                        job.join()
                    }
                }

                assertTrue(events.isNotEmpty())
                assertTrue(events.any { it is FileEvent.Created })
            } finally {
                fileIndexer.close()
            }
        }

    @Test
    fun `watchForWord should emit search results for matching files`() =
        runTest {
            val fileIndexer =
                FileIndexer.builder()
                    .addPath(tempDir.toString())
                    .build()

            fileIndexer.start()

            try {
                val results = mutableListOf<SearchResult>()
                val watchJob =
                    launch {
                        // First collect initial results, then wait for one more
                        fileIndexer.watchForWord("test").take(2).toList(results)
                    }

                withContext(Dispatchers.IO) {
                    delay(500) // Give time for initial results and watcher setup

                    val newFile = tempDir.resolve("newtest.txt")
                    Files.write(newFile, "test content here".toByteArray())
                }

                // Wait for results with real time
                withContext(Dispatchers.Default.limitedParallelism(1)) {
                    withTimeout(3000) {
                        watchJob.join()
                    }
                }

                assertTrue(results.isNotEmpty())
                assertTrue(results.any { it.matches.contains("test") })
            } finally {
                fileIndexer.close()
            }
        }

    @Test
    fun `watchForWords should emit updated search results`() =
        runTest {
            val fileIndexer =
                FileIndexer.builder()
                    .addPath(tempDir.toString())
                    .build()

            fileIndexer.start()

            try {
                val resultsList = mutableListOf<List<SearchResult>>()
                val watchJob =
                    launch {
                        // Take initial results plus one update
                        fileIndexer.watchForWords(listOf("hello", "test")).take(2).toList(resultsList)
                    }

                withContext(Dispatchers.IO) {
                    delay(500) // Give time for initial results and watcher setup

                    val newFile = tempDir.resolve("hello_test.txt")
                    Files.write(newFile, "hello test content".toByteArray())
                }

                withContext(Dispatchers.Default.limitedParallelism(1)) {
                    withTimeout(3000) {
                        watchJob.join()
                    }
                }

                assertTrue(resultsList.isNotEmpty())
                assertTrue(resultsList.size >= 1)
            } finally {
                fileIndexer.close()
            }
        }

    @Test
    fun `stop should cancel monitoring`() =
        runTest {
            val fileIndexer =
                FileIndexer.builder()
                    .addPath(tempDir.toString())
                    .build()

            fileIndexer.start()
            fileIndexer.stop()

            // After stopping, file monitoring should not be active
            // This is more of a state test - we verify it doesn't crash
            assertDoesNotThrow {
                runBlocking { fileIndexer.stop() } // Should be safe to call multiple times
            }
        }

    @Test
    fun `close should clean up resources`() =
        runTest {
            val fileIndexer =
                FileIndexer.builder()
                    .addPath(tempDir.toString())
                    .build()

            fileIndexer.start()

            assertDoesNotThrow {
                fileIndexer.close()
            }

            val results = fileIndexer.search("hello")
            assertTrue(results.isEmpty())
        }

    @Test
    fun `indexing should handle non-existent files gracefully`() =
        runTest {
            val nonExistentPath = tempDir.resolve("nonexistent.txt").toString()
            val fileIndexer =
                FileIndexer.builder()
                    .addPath(nonExistentPath)
                    .build()

            assertDoesNotThrow {
                runBlocking { fileIndexer.start() }
            }

            try {
                val results = fileIndexer.search("anything")
                assertTrue(results.isEmpty())
            } finally {
                fileIndexer.close()
            }
        }

    @Test
    fun `indexing should handle non-existent directories gracefully`() =
        runTest {
            val nonExistentDir = tempDir.resolve("nonexistent").toString()
            val fileIndexer =
                FileIndexer.builder()
                    .addPath(nonExistentDir)
                    .build()

            assertDoesNotThrow {
                runBlocking { fileIndexer.start() }
            }

            try {
                val results = fileIndexer.search("anything")
                assertTrue(results.isEmpty())
            } finally {
                fileIndexer.close()
            }
        }

    @Test
    fun `custom tokenizer should be used for indexing and searching`() =
        runTest {
            val mockTokenizer =
                mock<Tokenizer> {
                    on { tokenize(any()) } doReturn setOf("mocked", "tokens")
                    on { normalize(any()) } doAnswer { it.arguments[0] as String }
                }

            val fileIndexer =
                FileIndexer.builder()
                    .addPath(testFile1.toString())
                    .tokenizer(mockTokenizer)
                    .build()

            fileIndexer.start()

            try {
                verify(mockTokenizer, atLeastOnce()).tokenize(any())

                fileIndexer.search("test")
                verify(mockTokenizer, atLeastOnce()).normalize("test")
            } finally {
                fileIndexer.close()
            }
        }

    @Test
    fun `search results should contain correct file path and matches`() =
        runTest {
            val fileIndexer =
                FileIndexer.builder()
                    .addPath(tempDir.toString())
                    .build()

            fileIndexer.start()

            try {
                val results = fileIndexer.search("programming")
                assertTrue(results.isNotEmpty())

                results.forEach { result ->
                    assertNotNull(result.file)
                    assertTrue(result.file.isNotEmpty())
                    assertTrue(result.matches.contains("programming"))
                }
            } finally {
                fileIndexer.close()
            }
        }

    @Test
    fun `concurrent searches should work correctly`() =
        runTest {
            val fileIndexer =
                FileIndexer.builder()
                    .addPath(tempDir.toString())
                    .build()

            fileIndexer.start()

            try {
                // Launch multiple concurrent searches
                val job1 = launch { fileIndexer.search("hello") }
                val job2 = launch { fileIndexer.search("world") }
                val job3 = launch { fileIndexer.search("programming") }

                // All should complete without issues
                job1.join()
                job2.join()
                job3.join()
            } finally {
                fileIndexer.close()
            }
        }
}
