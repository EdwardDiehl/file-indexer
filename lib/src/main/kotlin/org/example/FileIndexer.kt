package org.example

import org.example.lib.tokenizers.SimpleTokenizer
import org.example.lib.tokenizers.Tokenizer

class FileIndexer private constructor(
    private val paths: List<String>,
    private val tokenizer: Tokenizer,
    private val fileFilter: (String) -> Boolean,
) {
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

    //
}
