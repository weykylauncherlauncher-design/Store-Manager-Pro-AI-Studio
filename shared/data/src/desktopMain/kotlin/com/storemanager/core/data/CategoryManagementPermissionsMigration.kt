package com.storemanager.core.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

class CategoryManagementPermissionsMigration(
    private val database: StoreDatabase,
    private val driver: SqlDriver
) {
    fun migrate(): Boolean = database.transactionWithResult {
        if (isApplied()) return@transactionWithResult false

        val queries = database.storeDatabaseQueries
        val tenants = queries.selectAllTenants().executeAsList()
        if (tenants.isEmpty()) return@transactionWithResult false

        val administrators = tenants.map { tenant ->
            val roles = queries.selectRolesByTenantId(tenant.id).executeAsList()
            val keyedAdministrators = roles.filter { it.system_key == ADMINISTRATOR_KEY }
            val canonicalAdministrators = roles.filter {
                it.name == ADMINISTRATOR_NAME && it.description == ADMINISTRATOR_DESCRIPTION
            }

            check(keyedAdministrators.size == 1) {
                "Tenant ${tenant.id} must have exactly one recognized Administrator system role"
            }
            val administrator = keyedAdministrators.single()
            check(
                administrator.is_system == 1L &&
                    canonicalAdministrators.size == 1 &&
                    canonicalAdministrators.single().id == administrator.id
            ) {
                "Administrator system role identity is ambiguous or conflicting in tenant ${tenant.id}"
            }
            administrator
        }

        categoryPermissions.forEach { permission ->
            val existing = queries.selectPermissionByCode(permission.code).executeAsOneOrNull()
            if (existing == null) {
                queries.insertPermission(permission.code, permission.category, permission.description)
            } else {
                check(
                    existing.category == permission.category &&
                        existing.description == permission.description
                ) {
                    "Conflicting category management permission definition: ${permission.code}"
                }
            }
        }

        administrators.forEach { administrator ->
            val existingCodes = queries.selectPermissionCodesByRoleId(administrator.id)
                .executeAsList()
                .toSet()
            categoryPermissions.map { it.code }
                .filterNot(existingCodes::contains)
                .forEach { code ->
                    queries.insertRolePermission(administrator.id, code)
                }
        }

        recordApplied()
        true
    }

    private fun isApplied(): Boolean = driver.executeQuery(
        null,
        "SELECT EXISTS(SELECT 1 FROM app_data_migrations WHERE migration_id = '$MIGRATION_ID')",
        {
            it.next()
            QueryResult.Value((it.getLong(0) ?: 0L) != 0L)
        },
        0
    ).value

    private fun recordApplied() {
        driver.execute(
            null,
            "INSERT INTO app_data_migrations(migration_id) VALUES (?)",
            1
        ) {
            bindString(0, MIGRATION_ID)
        }
    }

    private data class CategoryPermission(
        val code: String,
        val category: String,
        val description: String
    )

    private companion object {
        const val MIGRATION_ID = "CATEGORY_MANAGEMENT_PERMISSIONS_V1"
        const val ADMINISTRATOR_KEY = "ADMINISTRATOR"
        const val ADMINISTRATOR_NAME = "Administrator"
        const val ADMINISTRATOR_DESCRIPTION = "Full system administration"

        val categoryPermissions = listOf(
            CategoryPermission("CATEGORY_CREATE", "CATEGORIES", "Create product categories"),
            CategoryPermission("CATEGORY_UPDATE", "CATEGORIES", "Update product categories"),
            CategoryPermission("CATEGORY_DEACTIVATE", "CATEGORIES", "Deactivate product categories")
        )
    }
}
