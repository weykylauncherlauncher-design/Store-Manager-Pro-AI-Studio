package com.storemanager.core.data

import kotlinx.coroutines.CancellationException

/** An in-memory authentication result; credential material is deliberately excluded. */
data class AuthenticatedSession(
    val userId: String,
    val tenantId: String,
    val storeId: String,
    val username: String,
    val fullName: String,
    val roleIds: Set<String>,
    val permissionCodes: Set<String>
)

class AuthenticationFailedException : Exception(AUTHENTICATION_FAILED_MESSAGE) {
    private companion object {
        const val AUTHENTICATION_FAILED_MESSAGE = "Invalid username or password"
    }
}

/**
 * Authenticates a user in the supplied tenant and active store.
 *
 * Failure messages are intentionally uniform, but execution time is not: an unknown user or
 * invalid tenant/store is rejected before Argon2 runs. This service does not promise timing
 * equalization.
 */
class AuthenticationService(
    private val database: StoreDatabase,
    private val passwordHasher: Argon2idPasswordHasher = Argon2idPasswordHasher()
) {
    suspend fun authenticate(
        tenantId: String,
        storeId: String,
        username: String,
        password: CharArray
    ): AuthenticatedSession {
        try {
            val queries = database.storeDatabaseQueries
            val tenant = queries.selectTenantById(tenantId).executeAsOneOrNull()
                ?: throw AuthenticationFailedException()
            val store = queries.selectStoresByTenantId(tenantId).executeAsList()
                .singleOrNull { it.id == storeId && it.is_active == 1L }
                ?: throw AuthenticationFailedException()
            if (tenant.is_active != 1L) throw AuthenticationFailedException()

            val user = queries.selectUserByTenantAndUsername(tenantId, username).executeAsOneOrNull()
                ?: throw AuthenticationFailedException()

            val passwordMatches = try {
                passwordHasher.verifyPassword(password, user.password_hash, user.salt)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IllegalArgumentException) {
                false
            }

            if (!passwordMatches || user.is_active != 1L) {
                throw AuthenticationFailedException()
            }

            val roleIds = queries.selectRoleIdsByUserId(user.id).executeAsList().toSet()
            val tenantRoleIds = queries.selectRolesByTenantId(tenantId)
                .executeAsList()
                .mapTo(mutableSetOf()) { it.id }
            if (!tenantRoleIds.containsAll(roleIds)) {
                throw AuthenticationFailedException()
            }

            val permissionCodes = roleIds
                .flatMap { roleId ->
                    queries.selectPermissionCodesByRoleId(roleId).executeAsList()
                }
                .toSet()
            val existingPermissionCodes = queries.selectAllPermissions()
                .executeAsList()
                .mapTo(mutableSetOf()) { it.code }

            return AuthenticatedSession(
                userId = user.id,
                tenantId = tenantId,
                storeId = store.id,
                username = user.username,
                fullName = user.full_name,
                roleIds = roleIds,
                permissionCodes = permissionCodes.intersect(existingPermissionCodes)
            )
        } finally {
            password.fill('\u0000')
        }
    }
}
