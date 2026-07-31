package dev.leonardo.octether.domain.model

import io.ktor.http.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiResultTest {

    // ============ ApiResult construction ============

    @Test
    fun `Success holds data`() {
        val result = ApiResult.Success("hello")
        assertEquals("hello", result.data)
    }

    @Test
    fun `Error holds ApiError`() {
        val error = ApiError.AuthError
        val result = ApiResult.Error<String>(error)
        assertEquals(error, result.error)
    }

    @Test
    fun `isSuccess returns true for Success`() {
        assertTrue((ApiResult.Success(42) as ApiResult<Int>).isSuccess)
    }

    @Test
    fun `isSuccess returns false for Error`() {
        val result: ApiResult<Int> = ApiResult.Error(ApiError.ServerError(500))
        assertTrue(!result.isSuccess)
    }

    @Test
    fun `getOrNull returns data for Success`() {
        val result: ApiResult<String> = ApiResult.Success("data")
        assertEquals("data", result.getOrNull())
    }

    @Test
    fun `getOrNull returns null for Error`() {
        val result: ApiResult<String> = ApiResult.Error(ApiError.NotFoundError)
        assertNull(result.getOrNull())
    }

    @Test
    fun `getOrDefault returns data for Success`() {
        val result: ApiResult<Int> = ApiResult.Success(10)
        assertEquals(10, result.getOrDefault(0))
    }

    @Test
    fun `getOrDefault returns default for Error`() {
        val result: ApiResult<Int> = ApiResult.Error(ApiError.NetworkError)
        assertEquals(0, result.getOrDefault(0))
    }

    // ============ mapHttpError ============

    @Test
    fun `401 maps to AuthError`() {
        val error = mapHttpError(HttpStatusCode.Unauthorized.value)
        assertTrue(error is ApiError.AuthError)
    }

    @Test
    fun `403 maps to ForbiddenError`() {
        val error = mapHttpError(HttpStatusCode.Forbidden.value)
        assertTrue(error is ApiError.ForbiddenError)
    }

    @Test
    fun `404 maps to NotFoundError`() {
        val error = mapHttpError(HttpStatusCode.NotFound.value)
        assertTrue(error is ApiError.NotFoundError)
    }

    @Test
    fun `429 without headers maps to RateLimitError with zero retry`() {
        val error = mapHttpError(HttpStatusCode.TooManyRequests.value)
        assertTrue(error is ApiError.RateLimitError)
        assertEquals(0L, (error as ApiError.RateLimitError).retryAfterMillis)
    }

    @Test
    fun `429 with retry-after header maps to RateLimitError`() {
        val error = mapHttpError(
            HttpStatusCode.TooManyRequests.value,
            retryAfterSeconds = "30"
        )
        assertTrue(error is ApiError.RateLimitError)
        assertEquals(30_000L, (error as ApiError.RateLimitError).retryAfterMillis)
    }

    @Test
    fun `429 with retry-after-ms header maps to RateLimitError`() {
        val error = mapHttpError(
            HttpStatusCode.TooManyRequests.value,
            retryAfterMs = "5000"
        )
        assertTrue(error is ApiError.RateLimitError)
        assertEquals(5000L, (error as ApiError.RateLimitError).retryAfterMillis)
    }

    @Test
    fun `429 prefers retry-after-ms over retry-after`() {
        val error = mapHttpError(
            HttpStatusCode.TooManyRequests.value,
            retryAfterSeconds = "30",
            retryAfterMs = "5000"
        )
        assertEquals(5000L, (error as ApiError.RateLimitError).retryAfterMillis)
    }

    @Test
    fun `500 maps to ServerError`() {
        val error = mapHttpError(HttpStatusCode.InternalServerError.value)
        assertTrue(error is ApiError.ServerError)
        assertEquals(500, (error as ApiError.ServerError).statusCode)
    }

    @Test
    fun `502 maps to ServerError`() {
        val error = mapHttpError(HttpStatusCode.BadGateway.value)
        assertTrue(error is ApiError.ServerError)
        assertEquals(502, (error as ApiError.ServerError).statusCode)
    }

    @Test
    fun `503 maps to ServerError`() {
        val error = mapHttpError(HttpStatusCode.ServiceUnavailable.value)
        assertTrue(error is ApiError.ServerError)
        assertEquals(503, (error as ApiError.ServerError).statusCode)
    }

    @Test
    fun `400 maps to ClientError`() {
        val error = mapHttpError(HttpStatusCode.BadRequest.value)
        assertTrue(error is ApiError.ClientError)
        assertEquals(400, (error as ApiError.ClientError).statusCode)
    }

    @Test
    fun `isTransient returns true for ServerError`() {
        assertTrue((ApiError.ServerError(500)).isTransient)
    }

    @Test
    fun `isTransient returns true for RateLimitError`() {
        assertTrue((ApiError.RateLimitError(1000L)).isTransient)
    }

    @Test
    fun `isTransient returns false for AuthError`() {
        assertTrue(!ApiError.AuthError.isTransient)
    }

    @Test
    fun `isTransient returns false for NotFoundError`() {
        assertTrue(!ApiError.NotFoundError.isTransient)
    }

    @Test
    fun `isTransient returns true for NetworkError`() {
        assertTrue(ApiError.NetworkError.isTransient)
    }
}
