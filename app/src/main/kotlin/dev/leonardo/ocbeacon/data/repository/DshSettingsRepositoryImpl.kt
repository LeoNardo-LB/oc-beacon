package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.data.api.dsh.DshApiClient
import dev.leonardo.ocbeacon.domain.model.DshPermissionDefault
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.repository.DshSettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DshSettingsRepositoryImpl @Inject constructor(
    private val dshApi: DshApiClient,
) : DshSettingsRepository {

    override suspend fun getPermissionDefault(conn: ServerConnection): DshPermissionDefault? =
        dshApi.getPermissionDefault(conn)

    override suspend fun setPermissionDefault(conn: ServerConnection, preset: String): Boolean =
        dshApi.setPermissionDefault(conn, preset)
}
