package com.storemanager.core.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DatabaseDriverFactorySchemaMigrationTest {
    private val fixtureFiles = mutableListOf<File>()
    private val openDrivers = mutableListOf<SqlDriver>()

    @After
    fun tearDown() {
        openDrivers.forEach { try { it.close() } catch (_: Throwable) {} }
        fixtureFiles.forEach { file ->
            file.delete()
            File("${file.absolutePath}-wal").delete()
            File("${file.absolutePath}-shm").delete()
            File("${file.parentFile.absolutePath}/${file.name}.init.lock").delete()
        }
    }

    @Test
    fun matchingV1FixtureMigratesThroughV2AndReopensAsV3() {
        val file = createV1Fixture()
        val driver = DatabaseDriverFactory(file).createDriver()
        openDrivers += driver

        assertEquals(3L, queryLong(driver, "PRAGMA user_version"))
        assertEquals(56L, queryLong(driver, "SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"))
        assertEquals(109L, queryLong(driver, "SELECT count(*) FROM sqlite_master WHERE type='index' AND sql IS NOT NULL"))
        assertEquals(188L, queryLong(driver, "SELECT count(*) FROM sqlite_master WHERE type='trigger'"))
        assertEquals("Migration Tenant", queryString(driver, "SELECT name FROM tenants WHERE id='TENANT_V1_FIXTURE'"))
        assertEquals("Existing Role", queryString(driver, "SELECT name FROM roles WHERE id='ROLE_V1_FIXTURE'"))
        assertEquals(
            "KEEP_EXISTING",
            queryString(driver, "SELECT permission_code FROM role_permissions WHERE role_id='ROLE_V1_FIXTURE'")
        )
        assertEquals(0L, queryLong(driver, "SELECT system_key IS NOT NULL FROM roles WHERE id='ROLE_V1_FIXTURE'"))
        assertEquals("ok", queryString(driver, "PRAGMA integrity_check"))

        assertThrows(Exception::class.java) {
            driver.execute(
                null,
                "INSERT INTO tax_rates (id, tenant_id, name, rate_bps, is_active) VALUES ('TAX_INVALID', 'TENANT_V1_FIXTURE', 'Invalid', 10001, 1)",
                0
            )
        }

        driver.close()
        openDrivers.remove(driver)
        val reopened = DatabaseDriverFactory(file).createDriver()
        openDrivers += reopened
        assertEquals(3L, queryLong(reopened, "PRAGMA user_version"))
        assertEquals(188L, queryLong(reopened, "SELECT count(*) FROM sqlite_master WHERE type='trigger'"))
    }

    @Test
    fun migrationFailureRollsBackSchemaAndVersion() {
        val file = createV1Fixture()
        assertTrue(file.exists())
        val fixtureDriver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        assertEquals(1L, queryLong(fixtureDriver, "PRAGMA user_version"))
        fixtureDriver.close()
        val factory = DatabaseDriverFactory(file)
        factory.simulateMigrationFailure = true
        assertTrue(factory.simulateMigrationFailure)
        val rawDriver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        openDrivers += rawDriver
        assertEquals(1L, queryLong(rawDriver, "PRAGMA user_version"))
        assertThrows(DatabaseValidationException::class.java) {
            factory.validateExistingDatabase(rawDriver)
        }
        assertEquals(1L, queryLong(rawDriver, "PRAGMA user_version"))
        assertEquals(55L, queryLong(rawDriver, "SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"))
        assertEquals(107L, queryLong(rawDriver, "SELECT count(*) FROM sqlite_master WHERE type='index' AND sql IS NOT NULL"))
        assertEquals(0L, queryLong(rawDriver, "SELECT count(*) FROM pragma_table_info('roles') WHERE name='system_key'"))
        assertEquals(0L, queryLong(rawDriver, "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='app_data_migrations'"))
        assertEquals("Migration Tenant", queryString(rawDriver, "SELECT name FROM tenants WHERE id='TENANT_V1_FIXTURE'"))
    }

    @Test
    fun nonMatchingV1IsRejectedWithoutChangingDatabaseFile() {
        val file = createV1Fixture()
        val rawDriver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        rawDriver.execute(null, "CREATE TABLE unexpected_table(id TEXT);", 0)
        rawDriver.close()
        val before = file.readBytes()

        assertThrows(DatabaseValidationException::class.java) {
            DatabaseDriverFactory(file).createDriver()
        }

        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun unknownUserVersionIsRejectedWithoutChangingDatabaseFile() {
        val file = createV1Fixture()
        val rawDriver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        rawDriver.execute(null, "PRAGMA user_version = 99;", 0)
        rawDriver.close()
        val before = file.readBytes()

        assertThrows(DatabaseValidationException::class.java) {
            DatabaseDriverFactory(file).createDriver()
        }

        assertArrayEquals(before, file.readBytes())
    }

    private fun createV1Fixture(): File {
        val file = File.createTempFile("store-schema-v1-fixture-", ".db")
        fixtureFiles += file
        val snapshot = javaClass.classLoader.getResourceAsStream("schema-baselines/v1/1.db")
            ?: error("Packaged historical v1 snapshot was not found")
        snapshot.use { input -> file.outputStream().use(input::copyTo) }

        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        val database = StoreDatabase(driver)
        database.storeDatabaseQueries.insertTenant("TENANT_V1_FIXTURE", "Migration Tenant", 1)
        database.storeDatabaseQueries.insertRole(
            "ROLE_V1_FIXTURE",
            "TENANT_V1_FIXTURE",
            "Existing Role",
            "Keep this role",
            1
        )
        database.storeDatabaseQueries.insertPermission("KEEP_EXISTING", "TEST", "Keep this permission")
        database.storeDatabaseQueries.insertRolePermission("ROLE_V1_FIXTURE", "KEEP_EXISTING")
        val triggerScript = javaClass.classLoader
            .getResourceAsStream("schema-baselines/v1/canonical_triggers.sql")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("Packaged historical v1 trigger definitions were not found")
        triggerScript.split("CREATE TRIGGER")
            .filter { it.isNotBlank() }
            .forEach { driver.execute(null, "CREATE TRIGGER ${it.trim()}", 0) }
        driver.execute(null, "PRAGMA user_version = 1", 0)
        driver.close()
        return file
    }

    private fun queryLong(driver: SqlDriver, sql: String): Long =
        driver.executeQuery<Long>(
            null,
            sql,
            { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            0
        ).value

    private fun queryString(driver: SqlDriver, sql: String): String =
        driver.executeQuery<String>(
            null,
            sql,
            { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getString(0).orEmpty())
            },
            0
        ).value
}
