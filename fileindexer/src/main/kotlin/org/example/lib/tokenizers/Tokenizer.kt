package org.example.lib.tokenizers

interface Tokenizer {
    fun tokenize(content: String): Set<String>

    fun normalize(word: String): String = word.lowercase()
}
