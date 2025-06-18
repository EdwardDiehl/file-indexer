package org.example.lib

sealed class FileEvent {
    abstract val filePath: String

    data class Created(override val filePath: String) : FileEvent()

    data class Modified(override val filePath: String) : FileEvent()

    data class Deleted(override val filePath: String) : FileEvent()
}
