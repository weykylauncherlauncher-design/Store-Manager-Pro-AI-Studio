package com.storemanager.core.data

import java.util.UUID

data class FirstRunSetupResult(
    val tenantId: String,
    val storeId: String,
    val adminUserId: String
)

class FirstRunSetupService(
    private val database: StoreDatabase,
    private val passwordHasher: Argon2idPasswordHasher = Argon2idPasswordHasher()
) {
    suspend fun setup(
        tenantName: String,
        storeName: String,
        storeCode: String,
        adminFullName: String,
        adminUsername: String,
        adminPassword: CharArray
    ): FirstRunSetupResult {
        try {
            val normalizedTenantName = tenantName.trim().also(::requireNotEmpty)
            val normalizedStoreName = storeName.trim().also(::requireNotEmpty)
            val normalizedStoreCode = storeCode.trim().also(::requireNotEmpty)
            val normalizedAdminFullName = adminFullName.trim().also(::requireNotEmpty)
            val normalizedAdminUsername = adminUsername.trim().also(::requireNotEmpty)
            require(adminPassword.isNotEmpty()) { "Administrator password must not be empty" }

            val queries = database.storeDatabaseQueries
            check(queries.selectAllTenants().executeAsList().isEmpty()) {
                "First-run setup has already been completed"
            }

            val tenantId = newId("TENANT")
            val storeId = newId("STORE")
            val roleId = newId("ROLE")
            val adminUserId = newId("USER")
            val credentials = passwordHasher.createCredentials(adminPassword)

            database.transaction {
                check(queries.selectAllTenants().executeAsList().isEmpty()) {
                    "First-run setup has already been completed"
                }

                queries.insertTenant(tenantId, normalizedTenantName, 1)
                queries.insertStore(storeId, tenantId, normalizedStoreName, normalizedStoreCode)

                referenceUnits.forEach { unit ->
                    val existing = queries.selectUnitOfMeasureById(unit.id).executeAsOneOrNull()
                    if (existing == null) {
                        queries.insertUnitOfMeasure(
                            unit.id,
                            unit.dimension,
                            unit.nameAr,
                            unit.nameFr,
                            unit.symbol,
                            unit.isFractional.toLong()
                        )
                    } else {
                        check(
                            existing.dimension == unit.dimension &&
                                existing.name_ar == unit.nameAr &&
                                existing.name_fr == unit.nameFr &&
                                existing.symbol == unit.symbol &&
                                existing.is_fractional == unit.isFractional.toLong()
                        ) {
                            "Conflicting reference unit: ${unit.id}"
                        }
                    }
                }

                queries.insertSystemRole(
                    roleId,
                    tenantId,
                    ADMIN_ROLE_NAME,
                    ADMIN_ROLE_DESCRIPTION,
                    1,
                    ADMIN_ROLE_SYSTEM_KEY
                )

                administratorPermissions.forEach { permission ->
                    val existing = queries.selectPermissionByCode(permission.code).executeAsOneOrNull()
                    if (existing == null) {
                        queries.insertPermission(
                            permission.code,
                            permission.category,
                            permission.description
                        )
                    } else {
                        check(
                            existing.category == permission.category &&
                                existing.description == permission.description
                        ) {
                            "Conflicting reference permission: ${permission.code}"
                        }
                    }
                    queries.insertRolePermission(roleId, permission.code)
                }

                queries.insertUser(
                    adminUserId,
                    tenantId,
                    normalizedAdminUsername,
                    credentials.passwordHash,
                    credentials.salt,
                    normalizedAdminFullName,
                    1
                )
                queries.insertUserRole(adminUserId, roleId)
            }

            return FirstRunSetupResult(tenantId, storeId, adminUserId)
        } finally {
            adminPassword.fill('\u0000')
        }
    }

    private fun requireNotEmpty(value: String) {
        require(value.isNotEmpty()) { "Required setup fields must not be empty" }
    }

    private fun newId(prefix: String): String = "${prefix}_${UUID.randomUUID()}"

    private data class ReferenceUnit(
        val id: String,
        val dimension: String,
        val nameAr: String,
        val nameFr: String,
        val symbol: String,
        val isFractional: Int
    )

    private data class ReferencePermission(
        val code: String,
        val category: String,
        val description: String
    )

    private companion object {
        const val ADMIN_ROLE_NAME = "Administrator"
        const val ADMIN_ROLE_DESCRIPTION = "Full system administration"
        const val ADMIN_ROLE_SYSTEM_KEY = "ADMINISTRATOR"

        val referenceUnits = listOf(
            ReferenceUnit("UOM_PIECE", "COUNT", "قطعة", "Pièce", "pc", 0),
            ReferenceUnit("UOM_KG", "WEIGHT", "كيلوغرام", "Kilogramme", "kg", 1),
            ReferenceUnit("UOM_GRAM", "WEIGHT", "غرام", "Gramme", "g", 1),
            ReferenceUnit("UOM_METER", "LENGTH", "متر", "Mètre", "m", 1),
            ReferenceUnit("UOM_LITER", "VOLUME", "لتر", "Litre", "L", 1)
        )

        val administratorPermissions = listOf(
            ReferencePermission("SALE_CREATE", "SALES", "Create and complete sales"),
            ReferencePermission("INVENTORY_ADJUST", "INVENTORY", "Adjust inventory"),
            ReferencePermission("PAYMENT_CREATE", "FINANCE", "Create payments"),
            ReferencePermission("REPORT_VIEW", "REPORTS", "View reports"),
            ReferencePermission("PRODUCT_VIEW", "PRODUCTS", "View products"),
            ReferencePermission("PRODUCT_CREATE", "PRODUCTS", "Create products"),
            ReferencePermission("PRODUCT_UPDATE", "PRODUCTS", "Update products"),
            ReferencePermission("PRODUCT_DEACTIVATE", "PRODUCTS", "Deactivate and reactivate products"),
            ReferencePermission("CATEGORY_CREATE", "CATEGORIES", "Create product categories"),
            ReferencePermission("CATEGORY_UPDATE", "CATEGORIES", "Update product categories"),
            ReferencePermission("CATEGORY_DEACTIVATE", "CATEGORIES", "Deactivate product categories")
        )
    }
}
