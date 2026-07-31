package com.example

import com.example.model.TextTransferRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TextTransferTest {

    @Before
    fun setUp() {
        TextTransferRepository.clearAll()
    }

    @Test
    fun testAddSnippet() {
        val snippet = TextTransferRepository.addSnippet("Hello Portal", "Browser")
        assertEquals("Hello Portal", snippet.content)
        assertEquals("Browser", snippet.sender)

        val snippets = TextTransferRepository.snippets.value
        assertEquals(1, snippets.size)
        assertEquals(snippet.id, snippets[0].id)
    }

    @Test
    fun testDeleteSnippet() {
        val s1 = TextTransferRepository.addSnippet("First", "Phone")
        val s2 = TextTransferRepository.addSnippet("Second", "Browser")

        assertEquals(2, TextTransferRepository.snippets.value.size)

        TextTransferRepository.deleteSnippet(s1.id)
        val remaining = TextTransferRepository.snippets.value
        assertEquals(1, remaining.size)
        assertEquals(s2.id, remaining[0].id)
    }

    @Test
    fun testClearAll() {
        TextTransferRepository.addSnippet("Item 1", "Phone")
        TextTransferRepository.addSnippet("Item 2", "Phone")

        TextTransferRepository.clearAll()
        assertTrue(TextTransferRepository.snippets.value.isEmpty())
    }
}
