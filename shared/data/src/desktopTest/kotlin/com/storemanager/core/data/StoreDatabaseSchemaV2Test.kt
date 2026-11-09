package com.storemanager.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class StoreDatabaseSchemaV2Test {
    private lateinit var driver: JdbcSqliteDriver

    @Before
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        StoreDatabase.Schema.create(driver)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun partialUniqueSystemRoleKeyAllowsNullAndDifferentTenants() {
        val queries = StoreDatabase(driver).storeDatabaseQueries
        queries.insertTenant("TENANT_SCHEMA_A", "Tenant A", 1)
        queries.insertTenant("TENANT_SCHEMA_B", "Tenant B", 1)

        queries.insertRole("ROLE_CUSTOM_A1", "TENANT_SCHEMA_A", "Custom A1", null, 0)
        queries.insertRole("ROLE_CUSTOM_A2", "TENANT_SCHEMA_A", "Custom A2", null, 0)
        execute(
            "INSERT INTO roles(id, tenant_id, name, is_system, system_key) VALUES('ROLE_SYSTEM_A', 'TENANT_SCHEMA_A', 'Admin', 1, 'ADMINISTRATOR')"
        )
        execute(
            "INSERT INTO roles(id, tenant_id, name, is_system, system_key) VALUES('ROLE_SYSTEM_B', 'TENANT_SCHEMA_B', 'Admin', 1, 'ADMINISTRATOR')"
        )

        assertThrows(Exception::class.java) {
            execute(
                "INSERT INTO roles(id, tenant_id, name, is_system, system_key) VALUES('ROLE_DUPLICATE_SYSTEM_A', 'TENANT_SCHEMA_A', 'Admin Copy', 1, 'ADMINISTRATOR')"
            )
        }
    }

    @Test
    fun appDataMigrationIdIsUniqueAndAppliedAtHasDefault() {
        execute("INSERT INTO app_data_migrations(migration_id) VALUES('PRODUCT_PERMISSIONS_V1')")

        assertEquals("PRODUCT_PERMISSIONS_V1", queryString("SELECT migration_id FROM app_data_migrations"))
        assertTrue(queryString("SELECT applied_at FROM app_data_migrations").isNotBlank())
        assertThrows(Exception::class.java) {
            execute("INSERT INTO app_data_migrations(migration_id) VALUES('PRODUCT_PERMISSIONS_V1')")
        }
    }

    @Test
    fun sqlDelightMigrationFromV1PreservesExistingRowsAndObjects() {
        val snapshot = sequenceOf(File("schemas/1.db"), File("shared/data/schemas/1.db"))
            .firstOrNull(File::isFile)
            ?: error("Could not locate the unchanged SQLDelight v1 schema snapshot")
        val migratedFile = File.createTempFile("store-schema-v1-to-v2-", ".db")
        try {
            snapshot.copyTo(migratedFile, overwrite = true)
            driver.close()
            driver = JdbcSqliteDriver("jdbc:sqlite:${migratedFile.absolutePath}")
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)

            val v1Queries = StoreDatabase(driver).storeDatabaseQueries
            v1Queries.insertTenant("TENANT_MIGRATION", "Migration Tenant", 1)
            v1Queries.insertRole("ROLE_MIGRATION", "TENANT_MIGRATION", "Existing Role", "Keep this role", 1)
            v1Queries.insertPermission("OLD_PERMISSION", "TEST", "Keep existing permission")
            v1Queries.insertRolePermission("ROLE_MIGRATION", "OLD_PERMISSION")

            assertEquals(55L, queryLong("SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"))
            assertEquals(107L, queryLong("SELECT count(*) FROM sqlite_master WHERE type='index' AND sql IS NOT NULL"))

            StoreDatabase.Schema.migrate(driver, 1, 2)

            assertEquals(56L, queryLong("SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"))
            assertEquals(108L, queryLong("SELECT count(*) FROM sqlite_master WHERE type='index' AND sql IS NOT NULL"))
            assertEquals("Migration Tenant", queryString("SELECT name FROM tenants WHERE id='TENANT_MIGRATION'"))
            assertEquals("Existing Role", queryString("SELECT name FROM roles WHERE id='ROLE_MIGRATION'"))
            assertEquals("", queryString("SELECT system_key FROM roles WHERE id='ROLE_MIGRATION'"))
            assertEquals("OLD_PERMISSION", queryString("SELECT permission_code FROM role_permissions WHERE role_id='ROLE_MIGRATION'"))
            assertEquals(0L, queryLong("SELECT count(*) FROM app_data_migrations"))
        } finally {
            try {
                driver.close()
            } catch (_: Throwable) {
            }
            migratedFile.delete()
            File("${migratedFile.absolutePath}-wal").delete()
            File("${migratedFile.absolutePath}-shm").delete()
        }
    }

    private fun queryLong(sql: String): Long {
        val result = driver.executeQuery<Long>(
            null,
            sql,
            { cursor ->
                cursor.next()
                app.cash.sqldelight.db.QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            0
        )
        return result.value
    }

    private fun execute(sql: String) {
        driver.execute(null, sql, 0)
    }

    private fun queryString(sql: String): String {
        val result = driver.executeQuery<String>(
            null,
            sql,
            { cursor ->
                cursor.next()
                app.cash.sqldelight.db.QueryResult.Value(cursor.getString(0).orEmpty())
            },
            0
        )
        return result.value
    }
}
