package com.storemanager.core.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class CategoryManagementPermissionsMigrationTest {
    private lateinit var dbFile: File
    private lateinit var driver: SqlDriver
    private lateinit var database: StoreDatabase
    private lateinit var migration: CategoryManagementPermissionsMigration

    @Before
    fun setUp() {
        dbFile = File.createTempFile("category-management-permissions-", ".db")
        dbFile.delete()
        driver = DatabaseDriverFactory(dbFile).createDriver()
        database = StoreDatabase(driver)
        migration = CategoryManagementPermissionsMigration(database, driver)
    }

    @After
    fun tearDown() {
        try {
            driver.close()
        } finally {
            dbFile.delete()
            File("${dbFile.absolutePath}-wal").delete()
            File("${dbFile.absolutePath}-shm").delete()
            File("${dbFile.parentFile.absolutePath}/${dbFile.name}.init.lock").delete()
        }
    }

    @Test
    fun grantsCategoryManagementOnlyToRecognizedAdministrator() {
        addTenantWithSystemRoles("TENANT_PERMISSION_ASSIGNMENT")

        assertTrue(migration.migrate())

        assertEquals(CATEGORY_PERMISSIONS, permissionCodesFor(ADMIN_ID))
        assertTrue(permissionCodes().containsAll(CATEGORY_PERMISSIONS))
        assertTrue(permissionCodesFor(CASHIER_ID).isEmpty())
        assertTrue(permissionCodesFor(STOCK_MANAGER_ID).isEmpty())
        assertTrue(permissionCodesFor(CUSTOM_ROLE_ID).isEmpty())
        assertTrue(isMigrationRecorded())
    }

    @Test
    fun rerunningDoesNotDuplicateLinksAndDoesNotRestoreRemovedLinks() {
        addTenantWithSystemRoles("TENANT_PERMISSION_IDEMPOTENT")

        assertTrue(migration.migrate())
        val linksAfterFirstRun = scalarLong(
            "SELECT count(*) FROM role_permissions WHERE permission_code LIKE 'CATEGORY_%'"
        )
        assertFalse(migration.migrate())
        assertEquals(
            linksAfterFirstRun,
            scalarLong("SELECT count(*) FROM role_permissions WHERE permission_code LIKE 'CATEGORY_%'")
        )

        driver.execute(
            null,
            "DELETE FROM role_permissions WHERE role_id='$ADMIN_ID' AND permission_code='CATEGORY_CREATE'",
            0
        )
        assertFalse(migration.migrate())
        assertFalse(permissionCodesFor(ADMIN_ID).contains("CATEGORY_CREATE"))
    }

    @Test
    fun emptyDatabaseDoesNotRecordMigration() {
        assertFalse(migration.migrate())

        assertFalse(isMigrationRecorded())
        assertTrue(permissionCodes().isEmpty())
    }

    @Test
    fun administratorKeyWithConflictingMetadataFailsSafely() {
        val tenantId = "TENANT_ADMIN_CONFLICT"
        database.storeDatabaseQueries.insertTenant(tenantId, tenantId, 1)
        database.storeDatabaseQueries.insertSystemRole(
            "ROLE_CONFLICT",
            tenantId,
            "Custom Administrator",
            "Not the system administrator",
            1,
            "ADMINISTRATOR"
        )

        assertThrows(IllegalStateException::class.java) { migration.migrate() }

        assertFalse(isMigrationRecorded())
        assertTrue(permissionCodes().isEmpty())
        assertEquals("ADMINISTRATOR", systemKeyFor("ROLE_CONFLICT"))
    }

    @Test
    fun categoryPermissionDefinitionConflictRollsBackAllChanges() {
        addTenantWithSystemRoles("TENANT_PERMISSION_CONFLICT")
        database.storeDatabaseQueries.insertPermission(
            "CATEGORY_UPDATE",
            "OTHER",
            "Conflicting category permission"
        )

        assertThrows(IllegalStateException::class.java) { migration.migrate() }

        assertEquals(0L, scalarLong("SELECT count(*) FROM permissions WHERE code='CATEGORY_CREATE'"))
        assertEquals(0L, scalarLong("SELECT count(*) FROM role_permissions WHERE permission_code LIKE 'CATEGORY_%'"))
        assertFalse(isMigrationRecorded())
    }

    @Test
    fun failureRecordingMigrationRollsBackPermissionsAndLinks() {
        addTenantWithSystemRoles("TENANT_RECORD_FAILURE")
        driver.execute(
            null,
            """
            CREATE TRIGGER reject_category_permission_migration
            BEFORE INSERT ON app_data_migrations
            BEGIN
                SELECT RAISE(ABORT, 'injected migration-record failure');
            END
            """.trimIndent(),
            0
        )

        assertThrows(Exception::class.java) { migration.migrate() }

        assertEquals(0L, scalarLong("SELECT count(*) FROM permissions WHERE code LIKE 'CATEGORY_%'"))
        assertEquals(0L, scalarLong("SELECT count(*) FROM role_permissions WHERE permission_code LIKE 'CATEGORY_%'"))
        assertFalse(isMigrationRecorded())
    }

    private fun addTenantWithSystemRoles(tenantId: String) {
        database.storeDatabaseQueries.insertTenant(tenantId, tenantId, 1)
        database.storeDatabaseQueries.insertSystemRole(
            ADMIN_ID,
            tenantId,
            "Administrator",
            "Full system administration",
            1,
            "ADMINISTRATOR"
        )
        database.storeDatabaseQueries.insertSystemRole(
            CASHIER_ID,
            tenantId,
            "Cashier",
            "POS and cash operations",
            1,
            "CASHIER"
        )
        database.storeDatabaseQueries.insertSystemRole(
            STOCK_MANAGER_ID,
            tenantId,
            "Stock Manager",
            "Inventory and purchasing",
            1,
            "STOCK_MANAGER"
        )
        database.storeDatabaseQueries.insertRole(
            CUSTOM_ROLE_ID,
            tenantId,
            "Custom Supervisor",
            "Tenant-defined access",
            0
        )
    }

    private fun permissionCodesFor(roleId: String): Set<String> =
        database.storeDatabaseQueries.selectPermissionCodesByRoleId(roleId).executeAsList().toSet()

    private fun permissionCodes(): Set<String> =
        database.storeDatabaseQueries.selectAllPermissions().executeAsList().map { it.code }.toSet()

    private fun systemKeyFor(roleId: String): String? = driver.executeQuery(
        null,
        "SELECT system_key FROM roles WHERE id = '$roleId'",
        {
            if (it.next().value) QueryResult.Value(it.getString(0))
            else QueryResult.Value(null)
        },
        0
    ).value

    private fun isMigrationRecorded(): Boolean =
        scalarLong(
            "SELECT count(*) FROM app_data_migrations " +
                "WHERE migration_id='CATEGORY_MANAGEMENT_PERMISSIONS_V1'"
        ) == 1L

    private fun scalarLong(sql: String): Long = driver.executeQuery(
        null,
        sql,
        {
            it.next()
            QueryResult.Value(it.getLong(0) ?: 0L)
        },
        0
    ).value

    private companion object {
        const val ADMIN_ID = "ROLE_ADMIN"
        const val CASHIER_ID = "ROLE_CASHIER"
        const val STOCK_MANAGER_ID = "ROLE_STOCK_MANAGER"
        const val CUSTOM_ROLE_ID = "ROLE_CUSTOM"
        val CATEGORY_PERMISSIONS = setOf(
            "CATEGORY_CREATE",
            "CATEGORY_UPDATE",
            "CATEGORY_DEACTIVATE"
        )
    }
}
