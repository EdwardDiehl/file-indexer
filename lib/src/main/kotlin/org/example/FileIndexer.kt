package org.example

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.lib.IndexedFile
import org.example.lib.SearchResult
import org.example.lib.tokenizers.SimpleTokenizer
import org.example.lib.tokenizers.Tokenizer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.walk

class FileIndexer private constructor(
    private val paths: List<String>,
    private val tokenizer: Tokenizer,
    private val fileFilter: (String) -> Boolean,
) {
    // Thread-safe index storage
    private val filePathToIndexedFile = ConcurrentHashMap<String, IndexedFile>()
    private val tokenToFilePaths = ConcurrentHashMap<String, MutableSet<String>>()
    private val indexLock = ReentrantReadWriteLock()

    class Builder {
        private val paths = mutableListOf<String>()
        private var tokenizer: Tokenizer = SimpleTokenizer()
        private var fileFilter: (String) -> Boolean = { it.endsWith(".txt") }

        fun addPath(path: String) = apply { paths.add(path) }

        fun addPaths(vararg paths: String) = apply { this.paths.addAll(paths) }

        fun addPaths(paths: Collection<String>) = apply { this.paths.addAll(paths) }

        fun tokenizer(tokenizer: Tokenizer) = apply { this.tokenizer = tokenizer }

        fun fileFilter(filter: (String) -> Boolean) = apply { this.fileFilter = filter }

        fun build() = FileIndexer(paths.toList(), tokenizer, fileFilter)
    }

    companion object {
        fun builder() = Builder()
    }

    suspend fun search(word: String): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val normalizedWord = tokenizer.normalize(word)
            val matchingFiles = tokenToFilePaths[normalizedWord] ?: emptySet()

            indexLock.readLock().withLock {
                matchingFiles.mapNotNull { filePath ->
                    filePathToIndexedFile[filePath]?.let { indexedFile ->
                        SearchResult(file = filePath, matches = listOf(normalizedWord))
                    }
                }
            }
        }

    suspend fun search(words: Collection<String>): List<SearchResult> =
        withContext(Dispatchers.IO) {
            if (words.isEmpty()) return@withContext emptyList()

            indexLock.readLock().withLock {
                val normalizedWords = words.map { tokenizer.normalize(it) }.toSet()
                val fileToMatches = mutableMapOf<String, MutableSet<String>>()

                normalizedWords.forEach { word ->
                    tokenToFilePaths[word]?.forEach { filePath ->
                        fileToMatches.getOrPut(filePath) { mutableSetOf() }.add(word)
                    }
                }

                fileToMatches.map { (filePath, matchedWords) ->
                    SearchResult(file = filePath, matches = matchedWords.toList())
                }.sortedByDescending { it.matches.size }
            }
        }

    private suspend fun indexFile(filePath: String): IndexedFile? =
        withContext(Dispatchers.IO) {
            if (!fileFilter(filePath)) return@withContext null

            try {
                val fsPath = Path.of(filePath)

                if (!fsPath.exists() || !fsPath.isRegularFile()) return@withContext null

                val content = fsPath.readText()
                val tokens = tokenizer.tokenize(content)

                val lastModifiedFileTime = Files.getLastModifiedTime(fsPath)
                val lastModified = lastModifiedFileTime.toMillis()

                IndexedFile(path = fsPath.absolutePathString(), lastModified = lastModified, tokens = tokens)
            } catch (e: Exception) {
                println("Error indexing file $filePath: ${e.message}")
                null
            }
        }

    private fun updateIndex(indexedFile: IndexedFile) {
        indexLock.writeLock().withLock {
            val oldIndexedFile = filePathToIndexedFile[indexedFile.path]
            oldIndexedFile?.tokens?.forEach { token ->
                tokenToFilePaths[token]?.remove(indexedFile.path)
                if (tokenToFilePaths[token]?.isEmpty() == true) {
                    tokenToFilePaths.remove(token)
                }
            }

            filePathToIndexedFile[indexedFile.path] = indexedFile
            indexedFile.tokens.forEach { token ->
                tokenToFilePaths.getOrPut(token) { ConcurrentHashMap.newKeySet() }.add(indexedFile.path)
            }
        }
    }

    private suspend fun scanAndIndexPaths() =
        withContext(Dispatchers.IO) {
            paths.forEach { path ->
                val fsPath = Path.of(path)
                when {
                    fsPath.isRegularFile() -> {
                        indexFile(fsPath.absolutePathString())?.let { updateIndex(it) }
                    }
                    fsPath.isDirectory() -> {
                        fsPath.walk()
                            .filter { it.isRegularFile() && fileFilter(it.absolutePathString()) }
                            .forEach { file ->
                                indexFile(file.absolutePathString())?.let { updateIndex(it) }
                            }
                    }
                }
            }
        }

    suspend fun start() =
        withContext(Dispatchers.IO) {
            scanAndIndexPaths()
        }

    fun close() {
        filePathToIndexedFile.clear()
        tokenToFilePaths.clear()
    }
}
