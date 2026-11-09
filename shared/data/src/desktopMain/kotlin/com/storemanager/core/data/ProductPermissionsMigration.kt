package com.storemanager.core.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

class ProductPermissionsMigration(
    private val database: StoreDatabase,
    private val driver: SqlDriver
) {
    fun migrate(): Boolean = database.transactionWithResult {
        if (isApplied()) return@transactionWithResult false

        val queries = database.storeDatabaseQueries
        val tenants = queries.selectAllTenants().executeAsList()
        if (tenants.isEmpty()) return@transactionWithResult false

        val rolesByTenant = tenants.associate { tenant ->
            val roles = queries.selectRolesByTenantId(tenant.id).executeAsList()
            tenant.id to identifySystemRoles(tenant.id, roles)
        }

        productPermissions.forEach { permission ->
            val existing = queries.selectPermissionByCode(permission.code).executeAsOneOrNull()
            if (existing == null) {
                queries.insertPermission(permission.code, permission.category, permission.description)
            } else {
                check(
                    existing.category == permission.category &&
                        existing.description == permission.description
                ) {
                    "Conflicting product permission definition: ${permission.code}"
                }
            }
        }

        rolesByTenant.values.forEach { roles ->
            roles.forEach { (role, systemKey) ->
                if (role.system_key == null) {
                    driver.execute(
                        null,
                        "UPDATE roles SET system_key = ? WHERE id = ? AND system_key IS NULL",
                        2
                    ) {
                        bindString(0, systemKey)
                        bindString(1, role.id)
                    }
                }
            }

            val permissionsByRole = roles.associate { (role, _) ->
                role.id to queries.selectPermissionCodesByRoleId(role.id)
                    .executeAsList()
                    .toSet()
            }
            roles.forEach { (role, systemKey) ->
                val permittedCodes = if (systemKey == ADMINISTRATOR_KEY) {
                    productPermissions.map { it.code }
                } else {
                    listOf(PRODUCT_VIEW)
                }
                val existingCodes = permissionsByRole.getValue(role.id)
                permittedCodes.filterNot(existingCodes::contains).forEach { code ->
                    queries.insertRolePermission(role.id, code)
                }
            }
        }

        recordApplied()
        true
    }

    private fun identifySystemRoles(tenantId: String, roles: List<Roles>): List<Pair<Roles, String>> {
        return systemRoles.mapNotNull { definition ->
            val exactMetadataMatches = roles.filter { role ->
                role.name == definition.name && role.description == definition.description
            }
            val keyedRoles = roles.filter { it.system_key == definition.key }
            val invalidMetadataRole = exactMetadataMatches.firstOrNull { it.is_system != 1L }
            check(invalidMetadataRole == null) {
                "System role ${definition.key} in tenant $tenantId is not marked as a system role"
            }
            check(exactMetadataMatches.size <= 1) {
                "System role ${definition.key} is ambiguous in tenant $tenantId"
            }

            val role = exactMetadataMatches.singleOrNull()
            if (definition.key == ADMINISTRATOR_KEY) {
                check(role != null && role.is_system == 1L) {
                    "Tenant $tenantId must have exactly one recognized Administrator system role"
                }
            }

            if (role == null) {
                check(keyedRoles.isEmpty()) {
                    "System role key ${definition.key} conflicts with role metadata in tenant $tenantId"
                }
                null
            } else {
                check(role.is_system == 1L) {
                    "System role ${definition.key} in tenant $tenantId is not marked as a system role"
                }
                check(role.system_key == null || role.system_key == definition.key) {
                    "System role ${definition.key} has a conflicting system_key in tenant $tenantId"
                }
                check(keyedRoles.isEmpty() || keyedRoles.singleOrNull()?.id == role.id) {
                    "System role key ${definition.key} is assigned to another role in tenant $tenantId"
                }
                role to definition.key
            }
        }
    }

    private fun isApplied(): Boolean {
        val result = driver.executeQuery<Boolean>(
            null,
            "SELECT EXISTS(SELECT 1 FROM app_data_migrations WHERE migration_id = '$MIGRATION_ID')",
            {
                it.next()
                QueryResult.Value((it.getLong(0) ?: 0L) != 0L)
            },
            0
        )
        return result.value
    }

    private fun recordApplied() {
        driver.execute(
            null,
            "INSERT INTO app_data_migrations(migration_id) VALUES (?)",
            1
        ) {
            bindString(0, MIGRATION_ID)
        }
    }

    private data class SystemRoleDefinition(
        val key: String,
        val name: String,
        val description: String
    )

    private data class ProductPermission(
        val code: String,
        val category: String,
        val description: String
    )

    private companion object {
        const val MIGRATION_ID = "PRODUCT_PERMISSIONS_V1"
        const val ADMINISTRATOR_KEY = "ADMINISTRATOR"
        const val PRODUCT_VIEW = "PRODUCT_VIEW"

        val systemRoles = listOf(
            SystemRoleDefinition("ADMINISTRATOR", "Administrator", "Full system administration"),
            SystemRoleDefinition("CASHIER", "Cashier", "POS and cash operations"),
            SystemRoleDefinition("STOCK_MANAGER", "Stock Manager", "Inventory and purchasing")
        )

        val productPermissions = listOf(
            ProductPermission("PRODUCT_VIEW", "PRODUCTS", "View products"),
            ProductPermission("PRODUCT_CREATE", "PRODUCTS", "Create products"),
            ProductPermission("PRODUCT_UPDATE", "PRODUCTS", "Update products"),
            ProductPermission("PRODUCT_DEACTIVATE", "PRODUCTS", "Deactivate and reactivate products")
        )
    }
}
