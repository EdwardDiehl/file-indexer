package org.example.lib.tokenizers

class SimpleTokenizer : Tokenizer {
    override fun tokenize(content: String): Set<String> =
        content.lowercase()
            .split(Regex("\\W+"))
            .filter { it.isNotEmpty() }
            .toSet()
}
