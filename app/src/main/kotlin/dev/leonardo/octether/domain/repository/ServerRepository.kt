package dev.leonardo.octether.domain.repository

import dev.leonardo.octether.domain.model.ServerConnection

/**
 * Aggregate repository interface for server management.
 * Split into 4 sub-interfaces following ISP:
 * - [ServerConfigRepository]: Server CRUD
 * - [ServerConnectionRepository]: Connection lifecycle
 * - [LocalServerRepository]: Local server management
 * - [ProviderRepository]: Provider/model management
 */
interface ServerRepository :
    ServerConfigRepository,
    ServerConnectionRepository,
    LocalServerRepository,
    ProviderRepository {

    /**
     * Resolve server config to a [ServerConnection] for API calls.
     * Reused by other repositories (e.g. FileRepository) to avoid duplicating
     * the serverId→connection lookup logic.
     */
    suspend fun resolveConnection(serverId: String): ServerConnection
}
