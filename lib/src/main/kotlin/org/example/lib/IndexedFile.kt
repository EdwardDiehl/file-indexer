package org.example.lib

data class IndexedFile(
    val path: String,
    val lastModified: Long,
    val tokens: Set<String>,
)
