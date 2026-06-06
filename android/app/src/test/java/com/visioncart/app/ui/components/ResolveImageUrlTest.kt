package com.visioncart.app.ui.components

import org.junit.Assert.*
import org.junit.Test

/**
 * Regression tests for image URL resolution.
 * Ensures relative paths from backend are correctly resolved to absolute URLs,
 * and absolute/passthrough URLs are left unchanged.
 */
class ResolveImageUrlTest {

    // ==================== Relative path resolution ====================

    @Test
    fun `relative path starting with slash is prepended with API_BASE_URL`() {
        val result = resolveImageUrl("/api/v1/recognition/sess-123/candidates/candidate-1/image")
        assertNotNull(result)
        assertTrue("Should start with http", result!!.startsWith("http"))
        assertTrue("Should contain the path", result.contains("/api/v1/recognition/"))
    }

    @Test
    fun `relative history image path is resolved`() {
        val result = resolveImageUrl("/api/v1/history/sess-456/image")
        assertNotNull(result)
        assertTrue(result!!.contains("/api/v1/history/"))
    }

    // ==================== Absolute URL passthrough ====================

    @Test
    fun `http URL is returned as-is`() {
        val url = "http://img.example.com/product.jpg"
        assertEquals(url, resolveImageUrl(url))
    }

    @Test
    fun `https URL is returned as-is`() {
        val url = "https://img.example.com/product.jpg"
        assertEquals(url, resolveImageUrl(url))
    }

    @Test
    fun `file URL is returned as-is`() {
        val url = "file:///storage/emulated/0/DCIM/photo.jpg"
        assertEquals(url, resolveImageUrl(url))
    }

    @Test
    fun `content URL is returned as-is`() {
        val url = "content://media/external/images/123"
        assertEquals(url, resolveImageUrl(url))
    }

    // ==================== Null/blank handling ====================

    @Test
    fun `null URL returns null`() {
        assertNull(resolveImageUrl(null))
    }

    @Test
    fun `blank URL returns null`() {
        assertNull(resolveImageUrl("  "))
    }

    @Test
    fun `empty URL returns null`() {
        assertNull(resolveImageUrl(""))
    }

    @Test
    fun `upload scheme returns null`() {
        assertNull(resolveImageUrl("upload://sess-123"))
    }

    // ==================== Edge cases ====================

    @Test
    fun `URL with leading whitespace is trimmed`() {
        val result = resolveImageUrl("  /api/v1/history/sess/image  ")
        assertNotNull(result)
        assertTrue(result!!.contains("/api/v1/history/"))
    }
}
