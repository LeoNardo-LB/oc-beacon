package dev.leonardo.octether.data.api

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.body
import dev.leonardo.octether.data.dto.response.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OpenCodeApi @Inject constructor(
    private val httpClient: HttpClient,
) {
    suspend fun listDirectory(
        conn: ServerConnection,
        path: String,
        directory: String? = null,
    ): List<FileNodeDto> {
        return httpClient.get("${conn.baseUrl}/file/list") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
            parameter("path", path)
        }.body()
    }
