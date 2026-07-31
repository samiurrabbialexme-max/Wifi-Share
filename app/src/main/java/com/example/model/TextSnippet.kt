package com.example.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

data class TextSnippet(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val sender: String,
    val timestamp: Long = System.currentTimeMillis()
)

object TextTransferRepository {
    private val _snippets = MutableStateFlow<List<TextSnippet>>(emptyList())
    val snippets: StateFlow<List<TextSnippet>> = _snippets.asStateFlow()

    fun addSnippet(content: String, sender: String): TextSnippet {
        val snippet = TextSnippet(content = content.trim(), sender = sender)
        _snippets.update { (listOf(snippet) + it).take(100) }
        return snippet
    }

    fun clearAll() {
        _snippets.value = emptyList()
    }

    fun deleteSnippet(id: String) {
        _snippets.update { current -> current.filter { it.id != id } }
    }
}
