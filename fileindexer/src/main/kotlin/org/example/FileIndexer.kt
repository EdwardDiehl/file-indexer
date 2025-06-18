package org.example

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.example.lib.FileEvent
import org.example.lib.IndexedFile
import org.example.lib.SearchResult
import org.example.lib.tokenizers.SimpleTokenizer
import org.example.lib.tokenizers.Tokenizer
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.logging.Level
import java.util.logging.Logger
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
    private val coroutineIoDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val logger = Logger.getLogger(FileIndexer::class.java.name)

    // Thread-safe index storage
    private val filePathToIndexedFile = ConcurrentHashMap<String, IndexedFile>()
    private val tokenToFilePaths = ConcurrentHashMap<String, MutableSet<String>>()
    private val indexLock = ReentrantReadWriteLock()

    // File system monitoring
    private var watchService: WatchService? = null
    private var watchKeys = mutableMapOf<WatchKey, Path>()
    private var monitoringJob: Job? = null

    // Flow for file events
    private val fileEventFlow = MutableSharedFlow<FileEvent>(replay = 0, extraBufferCapacity = 100)

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
        withContext(coroutineIoDispatcher) {
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
        withContext(coroutineIoDispatcher) {
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

    // Watch methods with Flow for reactive streams
    fun watchForChanges(): Flow<FileEvent> = fileEventFlow.asSharedFlow()

    fun watchForWord(word: String): Flow<SearchResult> =
        callbackFlow {
            val normalizedWord = tokenizer.normalize(word)

            // Emit current results
            search(normalizedWord).forEach { trySend(it) }

            val watchForFsChangesJob =
                fileEventFlow
                    .filter { event ->
                        when (event) {
                            is FileEvent.Created, is FileEvent.Modified -> {
                                filePathToIndexedFile[event.filePath]?.tokens?.contains(normalizedWord) == true
                            }
                            is FileEvent.Deleted -> {
                                true
                            }
                        }
                    }
                    .onEach { event ->
                        when (event) {
                            is FileEvent.Created, is FileEvent.Modified -> {
                                filePathToIndexedFile[event.filePath]?.let { indexedFile ->
                                    if (indexedFile.tokens.contains(normalizedWord)) {
                                        trySend(SearchResult(file = event.filePath, matches = listOf(normalizedWord)))
                                    }
                                }
                            }
                            is FileEvent.Deleted -> {
                                // Don't emit anything for deleted files since the SearchResult would reference a non-existent file
                            }
                        }
                    }
                    .launchIn(this)

            awaitClose { watchForFsChangesJob.cancel() }
        }

    fun watchForWords(words: Collection<String>): Flow<List<SearchResult>> =
        callbackFlow {
            val normalizedWords = words.map { tokenizer.normalize(it) }.toSet()

            // Emit current results
            trySend(search(words))

            // Watch for changes
            val watchForFsChangesJob =
                fileEventFlow
                    .filter { event ->
                        when (event) {
                            is FileEvent.Created, is FileEvent.Modified -> {
                                filePathToIndexedFile[event.filePath]?.tokens?.any { it in normalizedWords } == true
                            }
                            is FileEvent.Deleted -> true
                        }
                    }
                    .onEach {
                        // Re-search and emit updated results
                        trySend(search(words))
                    }
                    .launchIn(this)

            awaitClose { watchForFsChangesJob.cancel() }
        }

    suspend fun start() =
        withContext(coroutineIoDispatcher) {
            scanAndIndexPaths()
            watchService = FileSystems.getDefault().newWatchService()

            paths.forEach { pathString ->
                val path = Paths.get(pathString)
                if (Files.isDirectory(path)) {
                    try {
                        val watchKey =
                            path.register(
                                watchService,
                                StandardWatchEventKinds.ENTRY_CREATE,
                                StandardWatchEventKinds.ENTRY_MODIFY,
                                StandardWatchEventKinds.ENTRY_DELETE,
                            )
                        watchKeys[watchKey] = path
                    } catch (e: Exception) {
                        logger.log(Level.WARNING, "Error registering watcher on directory $pathString", e)
                    }
                }
            }

            monitoringJob =
                CoroutineScope(coroutineIoDispatcher).launch {
                    monitorFileSystem()
                }
        }

    suspend fun stop() =
        withContext(coroutineIoDispatcher) {
            monitoringJob?.cancel()
            monitoringJob = null
            watchService?.close()
            watchService = null
            watchKeys.clear()
        }

    fun close() {
        runBlocking { stop() }
        filePathToIndexedFile.clear()
        tokenToFilePaths.clear()
    }

    private suspend fun indexFile(filePath: String): IndexedFile? =
        withContext(coroutineIoDispatcher) {
            if (!fileFilter(filePath)) return@withContext null

            try {
                val path = Path.of(filePath)

                if (!path.exists() || !path.isRegularFile()) return@withContext null

                val content = path.readText()
                val tokens = tokenizer.tokenize(content)

                val lastModifiedFileTime = Files.getLastModifiedTime(path)
                val lastModified = lastModifiedFileTime.toMillis()

                IndexedFile(path = path.absolutePathString(), lastModified = lastModified, tokens = tokens)
            } catch (e: Exception) {
                logger.log(Level.WARNING, "Error indexing file $filePath", e)
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

    private fun removeFromIndex(filePath: String) {
        indexLock.writeLock().withLock {
            val indexedFile = filePathToIndexedFile.remove(filePath)
            indexedFile?.tokens?.forEach { token ->
                tokenToFilePaths[token]?.remove(filePath)
                if (tokenToFilePaths[token]?.isEmpty() == true) {
                    tokenToFilePaths.remove(token)
                }
            }
        }
    }

    private suspend fun scanAndIndexPaths() =
        withContext(coroutineIoDispatcher) {
            paths.forEach { pathString ->
                val path = Path.of(pathString)
                when {
                    path.isRegularFile() -> {
                        indexFile(path.absolutePathString())?.let { updateIndex(it) }
                    }
                    path.isDirectory() -> {
                        path.walk()
                            .filter { it.isRegularFile() && fileFilter(it.absolutePathString()) }
                            .forEach { file ->
                                indexFile(file.absolutePathString())?.let { updateIndex(it) }
                            }
                    }
                }
            }
        }

    private suspend fun monitorFileSystem() {
        while (!Thread.currentThread().isInterrupted) {
            try {
                val currentWatchService = watchService ?: break
                val watchKey = currentWatchService.take()
                val basePath = watchKeys[watchKey] ?: continue

                watchKey.pollEvents().forEach { event ->
                    val eventKind = event.kind()
                    val fileName = event.context() as Path
                    val fullPath = basePath.resolve(fileName).toString()

                    if (!fileFilter(fullPath)) return@forEach

                    val fileEvent =
                        when (eventKind) {
                            StandardWatchEventKinds.ENTRY_CREATE -> {
                                indexFile(fullPath)?.let { updateIndex(it) }
                                FileEvent.Created(fullPath)
                            }
                            StandardWatchEventKinds.ENTRY_MODIFY -> {
                                indexFile(fullPath)?.let { updateIndex(it) }
                                FileEvent.Modified(fullPath)
                            }
                            StandardWatchEventKinds.ENTRY_DELETE -> {
                                removeFromIndex(fullPath)
                                FileEvent.Deleted(fullPath)
                            }
                            else -> null
                        }

                    fileEvent?.let { fileEventFlow.tryEmit(it) }
                }

                if (!watchKey.reset()) {
                    watchKeys.remove(watchKey)
                    if (watchKeys.isEmpty()) break
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: ClosedWatchServiceException) {
                // WatchService has been closed, exit gracefully
                break
            } catch (e: Exception) {
                logger.log(Level.WARNING, "Error monitoring filesystem changes", e)
            }
        }
    }
}
