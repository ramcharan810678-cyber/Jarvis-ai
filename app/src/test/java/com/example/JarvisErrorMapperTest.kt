package com.example

import com.example.data.JarvisErrorMapper
import com.example.data.JarvisErrorType
import com.example.data.JarvisModelOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class JarvisErrorMapperTest {

    @Test
    fun mapsAppCheckAndAuthErrorsClearly() {
        val error = JarvisErrorMapper.map(
            IllegalStateException("HTTP 403 App Check token rejected"),
            JarvisModelOption.default.modelId
        )
        assertEquals(JarvisErrorType.AUTH_APP_CHECK, error.type)
        assertTrue(error.canRetry)
    }

    @Test
    fun mapsQuotaExceededErrorsClearly() {
        val error = JarvisErrorMapper.map(
            RuntimeException("429 RESOURCE_EXHAUSTED: Quota exceeded"),
            JarvisModelOption.default.modelId
        )
        assertEquals(JarvisErrorType.QUOTA_EXCEEDED, error.type)
        assertFalse(error.canRetry)
        assertTrue(error.title.contains("429"))
        assertTrue(error.detail.contains("RESOURCE_EXHAUSTED"))
    }

    @Test
    fun mapsNetworkAndTimeoutErrorsClearly() {
        val hostError = JarvisErrorMapper.map(
            UnknownHostException("Unable to resolve host"),
            JarvisModelOption.default.modelId
        )
        assertEquals(JarvisErrorType.NETWORK_TIMEOUT, hostError.type)

        val timeoutError = JarvisErrorMapper.map(
            SocketTimeoutException("Read timed out"),
            JarvisModelOption.default.modelId
        )
        assertEquals(JarvisErrorType.NETWORK_TIMEOUT, timeoutError.type)
    }

    @Test
    fun mapsUnavailableModelErrorsClearly() {
        val error = JarvisErrorMapper.map(
            IllegalArgumentException("404 Model not found or deprecated"),
            "gemini-1.5-pro"
        )
        assertEquals(JarvisErrorType.MODEL_UNAVAILABLE, error.type)
        assertFalse(error.canRetry)
        assertTrue(error.detail.contains("gemini-1.5-pro"))
    }

    @Test
    fun supportedModelsAreLatestGemini3Series() {
        assertNotNull(JarvisModelOption.fromModelId("gemini-3.8-flash"))
        assertNotNull(JarvisModelOption.fromModelId("gemini-3.5-flash-lite"))
        assertTrue(JarvisModelOption.default.isSupportedByProject)
    }
}
