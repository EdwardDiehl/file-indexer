# FileIndexer Library

## Installation

Add the library to your project dependencies:

```kotlin
// Add to your build.gradle.kts
dependencies {
    implementation("org.example:fileindexer:0.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
```

## Quick Start

```kotlin
import org.example.FileIndexer
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    // Create and configure the indexer
    val indexer = FileIndexer.builder()
        .addPath("/path/to/documents")
        .addPath("/path/to/another/dir")
        .fileFilter { it.endsWith(".txt") || it.endsWith(".md") }
        .build()

    // Start indexing and monitoring
    indexer.start()

    // Search for files containing a word
    val results = indexer.search("kotlin")
    results.forEach { result ->
        println("Found '${result.matches}' in: ${result.file}")
    }

    // Clean up
    indexer.close()
}
```

## API Reference

### Builder Pattern

The `FileIndexer` uses a builder pattern for configuration:

```kotlin
val indexer = FileIndexer.builder()
    .addPath("/documents")                    // Add single path
    .addPaths("/src", "/tests")              // Add multiple paths
    .addPaths(listOf("/config", "/scripts")) // Add from collection
    .tokenizer(CustomTokenizer())            // Set custom tokenizer
    .fileFilter { file ->                    // Set file filter
        file.endsWith(".kt") || file.endsWith(".java")
    }
    .build()
```

### Search Methods

#### Single Word Search
```kotlin
suspend fun search(word: String): List<SearchResult>
```

Search for files containing a specific word:

```kotlin
val results = indexer.search("coroutines")
// Returns: List<SearchResult(file="/path/to/file.kt", matches=["coroutines"])>
```

#### Multiple Words Search
```kotlin
suspend fun search(words: Collection<String>): List<SearchResult>
```

Search for files containing any of the specified words:

```kotlin
val results = indexer.search(listOf("kotlin", "coroutines", "flow"))
// Returns results sorted by number of matching words (descending)
```

### Real-time Monitoring

#### Watch for File Changes
```kotlin
fun watchForChanges(): Flow<FileEvent>
```

Monitor all file system events:

```kotlin
indexer.watchForChanges()
    .collect { event ->
        when (event) {
            is FileEvent.Created -> println("File created: ${event.filePath}")
            is FileEvent.Modified -> println("File modified: ${event.filePath}")
            is FileEvent.Deleted -> println("File deleted: ${event.filePath}")
        }
    }
```

#### Watch for Specific Word
```kotlin
fun watchForWord(word: String): Flow<SearchResult>
```

Get real-time updates when files containing a specific word are modified:

```kotlin
indexer.watchForWord("TODO")
    .collect { result ->
        println("File with TODO updated: ${result.file}")
    }
```

#### Watch for Multiple Words
```kotlin
fun watchForWords(words: Collection<String>): Flow<List<SearchResult>>
```

Monitor changes to files containing any of the specified words:

```kotlin
indexer.watchForWords(listOf("bug", "fix", "error"))
    .collect { results ->
        println("Found ${results.size} files with bug-related content")
    }
```

### Lifecycle Management

```kotlin
// Start indexing and file monitoring
suspend fun start()

// Stop monitoring (keeps index in memory)
suspend fun stop()

// Complete cleanup (clears index and stops monitoring)
fun close()
```

## Configuration Options

### File Filtering

Control which files are indexed:

```kotlin
.fileFilter { filePath ->
    when {
        filePath.endsWith(".txt") -> true
        filePath.endsWith(".md") -> true
        filePath.contains("/.git/") -> false  // Exclude git files
        filePath.contains("/build/") -> false // Exclude build artifacts
        else -> false
    }
}
```

### Custom Tokenizers

Implement the `Tokenizer` interface for custom text processing:

```kotlin
class CustomTokenizer : Tokenizer {
    override fun tokenize(text: String): Set<String> {
        return text.toLowerCase()
            .split(Regex("\\W+"))
            .filter { it.length > 2 }
            .toSet()
    }

    override fun normalize(word: String): String {
        return word.toLowerCase().trim()
    }
}

// Use in builder
.tokenizer(CustomTokenizer())
```

## Data Classes

### SearchResult
```kotlin
data class SearchResult(
    val file: String,           // Absolute path to the file
    val matches: List<String>   // List of matched normalized tokens
)
```

### FileEvent
```kotlin
sealed class FileEvent {
    abstract val filePath: String

    data class Created(override val filePath: String) : FileEvent()
    data class Modified(override val filePath: String) : FileEvent()
    data class Deleted(override val filePath: String) : FileEvent()
}
```

### IndexedFile
```kotlin
data class IndexedFile(
    val path: String,           // Absolute file path
    val lastModified: Long,     // Last modification timestamp
    val tokens: Set<String>     // Normalized tokens from file content
)
```

## Advanced Usage

### Reactive Search Pipeline

Combine search with file monitoring for real-time search applications:

```kotlin
class SearchService(private val indexer: FileIndexer) {

    fun createSearchStream(query: String): Flow<List<SearchResult>> = flow {
        // Emit initial results
        emit(indexer.search(query))

        // Watch for changes and re-emit results
        indexer.watchForWord(query)
            .collect {
                emit(indexer.search(query))
            }
    }
}
```