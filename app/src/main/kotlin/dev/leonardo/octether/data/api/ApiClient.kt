package dev.leonardo.octether.data.api

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared HTTP client + JSON serializer holder for all domain API implementations.
 *
 * Each domain `*ApiImpl` injects this to access the Ktor [httpClient] and [json]
 * configured in [dev.leonardo.octether.di.NetworkModule]. Keeping both here avoids
 * every impl repeating the same constructor dependencies.
 */
@Singleton
class ApiClient @Inject constructor(
    val httpClient: HttpClient,
    val json: Json
)

/**
 * Attach the `x-opencode-directory` header to a request when [directory] is non-null.
 *
 * Shared by all domain Api implementations — extracted verbatim from the original
 * monolithic API class's `directoryHeader` private extension so method bodies can be moved
 * without modification.
 */
internal fun HttpRequestBuilder.directoryHeader(directory: String?) {
    directory?.let { header("x-opencode-directory", URLEncoder.encode(it, "UTF-8")) }
}
