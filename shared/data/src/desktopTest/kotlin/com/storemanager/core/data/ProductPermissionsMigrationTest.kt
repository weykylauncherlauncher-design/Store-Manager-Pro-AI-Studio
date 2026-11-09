package com.storemanager.core.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ProductPermissionsMigrationTest {
    private lateinit var dbFile: File
    private lateinit var driver: SqlDriver
    private lateinit var database: StoreDatabase
    private lateinit var migration: ProductPermissionsMigration

    @Before
    fun setUp() {
        dbFile = File.createTempFile("product-permissions-migration-", ".db")
        dbFile.delete()
        driver = DatabaseDriverFactory(dbFile).createDriver()
        database = StoreDatabase(driver)
        migration = ProductPermissionsMigration(database, driver)
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
    fun migratesAdministratorAndAssignsStableSystemKey() {
        addTenantWithAdministrator("TENANT_ADMIN")

        assertTrue(migration.migrate())

        assertEquals(
            setOf("PRODUCT_VIEW", "PRODUCT_CREATE", "PRODUCT_UPDATE", "PRODUCT_DEACTIVATE"),
            permissionCodesFor("ROLE_ADMIN_TENANT_ADMIN")
        )
        assertEquals("ADMINISTRATOR", systemKeyFor("ROLE_ADMIN_TENANT_ADMIN"))
        assertTrue(isMigrationRecorded())
    }

    @Test
    fun assignsViewOnlyToRecognizedCashierAndStockManager() {
        addTenantWithAdministrator("TENANT_ALL_ROLES")
        addSystemRole("TENANT_ALL_ROLES", "ROLE_CASHIER", "Cashier", "POS and cash operations")
        addSystemRole("TENANT_ALL_ROLES", "ROLE_STOCK", "Stock Manager", "Inventory and purchasing")

        assertTrue(migration.migrate())

        assertEquals(setOf("PRODUCT_VIEW"), permissionCodesFor("ROLE_CASHIER"))
        assertEquals(setOf("PRODUCT_VIEW"), permissionCodesFor("ROLE_STOCK"))
        assertEquals("CASHIER", systemKeyFor("ROLE_CASHIER"))
        assertEquals("STOCK_MANAGER", systemKeyFor("ROLE_STOCK"))
    }

    @Test
    fun leavesCustomRolesUnchangedAndPreservesExistingPermissionsAndLinks() {
        addTenantWithAdministrator("TENANT_CUSTOM")
        addSystemRole("TENANT_CUSTOM", "ROLE_CUSTOM", "Custom Supervisor", "Tenant-defined access")
        val queries = database.storeDatabaseQueries
        queries.insertPermission("OLD_PERMISSION", "LEGACY", "Preserve existing permission")
        queries.insertRolePermission("ROLE_ADMIN_TENANT_CUSTOM", "OLD_PERMISSION")
        queries.insertRolePermission("ROLE_CUSTOM", "OLD_PERMISSION")

        migration.migrate()

        assertEquals("OLD_PERMISSION", queries.selectPermissionByCode("OLD_PERMISSION").executeAsOne().code)
        assertTrue("Existing Administrator link must remain", permissionCodesFor("ROLE_ADMIN_TENANT_CUSTOM").contains("OLD_PERMISSION"))
        assertEquals(setOf("OLD_PERMISSION"), permissionCodesFor("ROLE_CUSTOM"))
        assertNull(systemKeyFor("ROLE_CUSTOM"))
    }

    @Test
    fun conflictingPermissionDefinitionRollsBackEverything() {
        addTenantWithAdministrator("TENANT_PERMISSION_CONFLICT")
        database.storeDatabaseQueries.insertPermission("PRODUCT_CREATE", "OTHER", "Conflicting description")

        assertThrows(IllegalStateException::class.java) { migration.migrate() }

        assertEquals(0L, scalarLong("SELECT count(*) FROM permissions WHERE code='PRODUCT_VIEW'"))
        assertEquals(0L, scalarLong("SELECT count(*) FROM app_data_migrations"))
        assertNull(systemKeyFor("ROLE_ADMIN_TENANT_PERMISSION_CONFLICT"))
        assertTrue(permissionCodesFor("ROLE_ADMIN_TENANT_PERMISSION_CONFLICT").isEmpty())
    }

    @Test
    fun systemKeyOnRoleWithDifferentMetadataIsRejectedWithoutGuessing() {
        addTenantWithAdministrator("TENANT_KEY_CONFLICT")
        addSystemRole("TENANT_KEY_CONFLICT", "ROLE_CUSTOM_KEY", "Custom Role", "Custom description")
        driver.execute(
            null,
            "UPDATE roles SET system_key = 'CASHIER' WHERE id = 'ROLE_CUSTOM_KEY'",
            0
        )

        assertThrows(IllegalStateException::class.java) { migration.migrate() }

        assertEquals(0L, scalarLong("SELECT count(*) FROM app_data_migrations"))
        assertEquals(0L, scalarLong("SELECT count(*) FROM permissions WHERE code LIKE 'PRODUCT_%'"))
        assertEquals("CASHIER", systemKeyFor("ROLE_CUSTOM_KEY"))
    }

    @Test
    fun missingOrUnrecognizedAdministratorIsRejected() {
        database.storeDatabaseQueries.insertTenant("TENANT_NO_ADMIN", "No Admin", 1)
        addSystemRole("TENANT_NO_ADMIN", "ROLE_LOOKALIKE", "Administrator", "Custom administrator")

        assertThrows(IllegalStateException::class.java) { migration.migrate() }

        assertEquals(0L, scalarLong("SELECT count(*) FROM app_data_migrations"))
        assertEquals(0L, scalarLong("SELECT count(*) FROM permissions WHERE code LIKE 'PRODUCT_%'"))
        assertNull(systemKeyFor("ROLE_LOOKALIKE"))
    }

    @Test
    fun nonSystemAdministratorLookalikeIsRejected() {
        database.storeDatabaseQueries.insertTenant("TENANT_NON_SYSTEM", "Non System", 1)
        addSystemRole("TENANT_NON_SYSTEM", "ROLE_NOT_SYSTEM", "Administrator", "Full system administration", isSystem = 0)

        assertThrows(IllegalStateException::class.java) { migration.migrate() }

        assertEquals(0L, scalarLong("SELECT count(*) FROM app_data_migrations"))
        assertNull(systemKeyFor("ROLE_NOT_SYSTEM"))
    }

    @Test
    fun failureRecordingMigrationRollsBackPermissionsLinksAndRoleKeys() {
        addTenantWithAdministrator("TENANT_ROLLBACK")
        driver.execute(
            null,
            """
            CREATE TRIGGER reject_product_permission_migration
            BEFORE INSERT ON app_data_migrations
            BEGIN
                SELECT RAISE(ABORT, 'injected migration-record failure');
            END
            """.trimIndent(),
            0
        )

        assertThrows(Exception::class.java) { migration.migrate() }

        assertEquals(0L, scalarLong("SELECT count(*) FROM permissions WHERE code LIKE 'PRODUCT_%'"))
        assertEquals(0L, scalarLong("SELECT count(*) FROM role_permissions WHERE permission_code LIKE 'PRODUCT_%'"))
        assertNull(systemKeyFor("ROLE_ADMIN_TENANT_ROLLBACK"))
        assertFalse(isMigrationRecorded())
    }

    @Test
    fun migrationIsRecordedOnceAndSubsequentCallsDoNotDuplicateLinks() {
        addTenantWithAdministrator("TENANT_IDEMPOTENT")

        assertTrue(migration.migrate())
        val countsBeforeRepeat = scalarLong("SELECT count(*) FROM role_permissions WHERE permission_code LIKE 'PRODUCT_%'")

        assertFalse(migration.migrate())

        assertEquals(countsBeforeRepeat, scalarLong("SELECT count(*) FROM role_permissions WHERE permission_code LIKE 'PRODUCT_%'"))
        assertEquals(1L, scalarLong("SELECT count(*) FROM app_data_migrations WHERE migration_id='PRODUCT_PERMISSIONS_V1'"))
    }

    @Test
    fun removedPermissionLinkIsNotRestoredAfterMigration() {
        addTenantWithAdministrator("TENANT_REMOVED_LINK")
        migration.migrate()
        driver.execute(
            null,
            "DELETE FROM role_permissions WHERE role_id='ROLE_ADMIN_TENANT_REMOVED_LINK' AND permission_code='PRODUCT_CREATE'",
            0
        )

        assertFalse(migration.migrate())

        assertFalse(permissionCodesFor("ROLE_ADMIN_TENANT_REMOVED_LINK").contains("PRODUCT_CREATE"))
    }

    @Test
    fun emptyDatabaseDoesNotRecordMigration() {
        assertFalse(migration.migrate())

        assertEquals(0L, scalarLong("SELECT count(*) FROM app_data_migrations"))
        assertEquals(0L, scalarLong("SELECT count(*) FROM permissions WHERE code LIKE 'PRODUCT_%'"))
    }

    @Test
    fun databaseManagerStartupMigratesOnceAndDoesNotRestoreRemovedLinks() {
        addTenantWithAdministrator("TENANT_STARTUP")
        driver.close()

        val manager = StoreDatabaseManager(DatabaseDriverFactory(dbFile))
        try {
            val openedDatabase = manager.getDatabase()
            val openedDriver = manager.getDriver()
            assertSame(openedDriver, manager.getDriver())

            assertTrue(ProductPermissionsMigration(openedDatabase, openedDriver).migrate())
            assertTrue(permissionCodesFor(openedDatabase, "ROLE_ADMIN_TENANT_STARTUP").contains("PRODUCT_CREATE"))
            assertEquals(1L, scalarLong(openedDriver, "SELECT count(*) FROM app_data_migrations"))

            openedDriver.execute(
                null,
                "DELETE FROM role_permissions WHERE role_id='ROLE_ADMIN_TENANT_STARTUP' AND permission_code='PRODUCT_CREATE'",
                0
            )
        } finally {
            manager.close()
        }

        val reopenedManager = StoreDatabaseManager(DatabaseDriverFactory(dbFile))
        try {
            val reopenedDatabase = reopenedManager.getDatabase()
            assertFalse(
                ProductPermissionsMigration(reopenedDatabase, reopenedManager.getDriver()).migrate()
            )
            assertFalse(permissionCodesFor(reopenedDatabase, "ROLE_ADMIN_TENANT_STARTUP").contains("PRODUCT_CREATE"))
            assertEquals(1L, scalarLong(reopenedManager.getDriver(), "SELECT count(*) FROM app_data_migrations"))
        } finally {
            reopenedManager.close()
        }
    }

    @Test
    fun emptyDatabaseRemainsEligibleForFirstRunAfterManagerStartup() {
        driver.close()
        val manager = StoreDatabaseManager(DatabaseDriverFactory(dbFile))
        try {
            val openedDatabase = manager.getDatabase()
            assertFalse(
                ProductPermissionsMigration(openedDatabase, manager.getDriver()).migrate()
            )
            assertTrue(
                StoreContextResolver(openedDatabase).resolve() is StoreContextResolution.NeedsFirstRunSetup
            )
            assertEquals(0L, scalarLong(manager.getDriver(), "SELECT count(*) FROM app_data_migrations"))
        } finally {
            manager.close()
        }
    }

    @Test
    fun migrationFailureStopsBeforeContextResolution() {
        addTenantWithAdministrator("TENANT_STARTUP_FAILURE")
        database.storeDatabaseQueries.insertPermission("PRODUCT_CREATE", "CONFLICT", "Incorrect definition")
        driver.close()

        val manager = StoreDatabaseManager(DatabaseDriverFactory(dbFile))
        var contextResolution: StoreContextResolution? = null
        var migrationFailed = false
        try {
            val openedDatabase = manager.getDatabase()
            try {
                ProductPermissionsMigration(openedDatabase, manager.getDriver()).migrate()
                contextResolution = StoreContextResolver(openedDatabase).resolve()
            } catch (_: IllegalStateException) {
                migrationFailed = true
            }
            assertTrue(migrationFailed)
            assertNull("Startup must not resolve context after migration failure", contextResolution)
            assertEquals(0L, scalarLong(manager.getDriver(), "SELECT count(*) FROM app_data_migrations"))
        } finally {
            manager.close()
        }
    }

    @Test
    fun schemaRejectedByFactoryDoesNotRunProductMigration() {
        addTenantWithAdministrator("TENANT_REJECTED_SCHEMA")
        driver.close()
        val rawDriver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        rawDriver.execute(null, "PRAGMA user_version = 99", 0)
        rawDriver.close()

        val manager = StoreDatabaseManager(DatabaseDriverFactory(dbFile))
        try {
            assertThrows(DatabaseValidationException::class.java) { manager.getDatabase() }
        } finally {
            manager.close()
        }

        val verificationDriver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        try {
            assertEquals(99L, scalarLong(verificationDriver, "PRAGMA user_version"))
            assertEquals(0L, scalarLong(verificationDriver, "SELECT count(*) FROM app_data_migrations"))
        } finally {
            verificationDriver.close()
        }
    }

    private fun addTenantWithAdministrator(tenantId: String) {
        database.storeDatabaseQueries.insertTenant(tenantId, tenantId, 1)
        addSystemRole(
            tenantId,
            "ROLE_ADMIN_$tenantId",
            "Administrator",
            "Full system administration"
        )
    }

    private fun addSystemRole(
        tenantId: String,
        roleId: String,
        name: String,
        description: String,
        isSystem: Int = 1
    ) {
        database.storeDatabaseQueries.insertRole(roleId, tenantId, name, description, isSystem.toLong())
    }

    private fun permissionCodesFor(roleId: String): Set<String> =
        database.storeDatabaseQueries.selectPermissionCodesByRoleId(roleId).executeAsList().toSet()

    private fun permissionCodesFor(database: StoreDatabase, roleId: String): Set<String> =
        database.storeDatabaseQueries.selectPermissionCodesByRoleId(roleId).executeAsList().toSet()

    private fun systemKeyFor(roleId: String): String? =
        driver.executeQuery<String?>(
            null,
            "SELECT system_key FROM roles WHERE id = '$roleId'",
            {
                if (it.next().value) QueryResult.Value(it.getString(0))
                else QueryResult.Value(null)
            },
            0
        ).value

    private fun isMigrationRecorded(): Boolean = scalarLong(
        "SELECT count(*) FROM app_data_migrations WHERE migration_id='PRODUCT_PERMISSIONS_V1'"
    ) == 1L

    private fun scalarLong(sql: String): Long =
        scalarLong(driver, sql)

    private fun scalarLong(driver: SqlDriver, sql: String): Long =
        driver.executeQuery<Long>(
            null,
            sql,
            {
                it.next()
                QueryResult.Value(it.getLong(0) ?: 0L)
            },
            0
        ).value
}
