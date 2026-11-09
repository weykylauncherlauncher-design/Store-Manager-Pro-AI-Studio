package com.storemanager.core.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TenantScopedSkuMigrationTest {
    private lateinit var driver: JdbcSqliteDriver

    @After
    fun tearDown() {
        if (::driver.isInitialized) driver.close()
    }

    @Test
    fun v2MigrationNormalizesVariantsPreservesRelationsAndScopesSkuToTenant() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        exec("PRAGMA foreign_keys = ON")
        installV2Baseline()
        seedV2Data()

        val priceBefore = scalarLong("SELECT unit_price_ticks FROM price_list_items WHERE id = 'PLI1'")
        val balanceBefore = scalarLong("SELECT on_hand_ticks FROM inventory_balances WHERE stock_item_id = 'SI1'")

        StoreDatabase(driver).transaction {
            TenantScopedSkuMigration(driver, currentTriggerScript()).migrate()
            exec("PRAGMA user_version = 3")
        }

        assertEquals(3L, scalarLong("PRAGMA user_version"))
        assertEquals("ABC-01", scalarString("SELECT sku FROM product_variants WHERE id = 'V1'"))
        assertEquals(priceBefore, scalarLong("SELECT unit_price_ticks FROM price_list_items WHERE id = 'PLI1'"))
        assertEquals(balanceBefore, scalarLong("SELECT on_hand_ticks FROM inventory_balances WHERE stock_item_id = 'SI1'"))
        println("SQLite version: ${scalarString("SELECT sqlite_version()")}")
        assertTrue(!scalarString("SELECT sqlite_version()").isNullOrBlank())

        exec("INSERT INTO product_variants(id, product_id, sku, variant_name, tenant_id) VALUES ('V2', 'P2', 'ABC-01', 'Other tenant', 'T2')")
        assertEquals(2L, scalarLong("SELECT COUNT(*) FROM product_variants"))

        assertConstraint("UNIQUE") {
            exec("INSERT INTO product_variants(id, product_id, sku, variant_name, tenant_id) VALUES ('V3', 'P1', 'ABC-01', 'Duplicate', 'T1')")
        }
        assertEquals(2L, scalarLong("SELECT COUNT(*) FROM product_variants"))

        assertConstraint("PRODUCT_VARIANT_TENANT_MISMATCH") {
            exec("INSERT INTO product_variants(id, product_id, sku, variant_name, tenant_id) VALUES ('V4', 'P1', 'CROSS-01', 'Cross tenant', 'T2')")
        }
        assertEquals(2L, scalarLong("SELECT COUNT(*) FROM product_variants"))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM pragma_foreign_key_check"))
        assertEquals("ok", scalarString("PRAGMA integrity_check"))
    }

    @Test
    fun v2MigrationRejectsNormalizedConflictBeforeChangingSchema() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        exec("PRAGMA foreign_keys = ON")
        installV2Baseline()
        seedV2Data()
        exec("INSERT INTO products(id, tenant_id, base_unit_id, name) VALUES ('P3', 'T1', 'UOM_PIECE', 'Second')")
        exec("INSERT INTO product_variants(id, product_id, sku, variant_name) VALUES ('V2', 'P3', 'abc-01', 'Conflict')")

        val oldSchema = scalarString("SELECT sql FROM sqlite_master WHERE type='table' AND name='product_variants'")
        assertConstraint("Normalized SKU conflict") {
            StoreDatabase(driver).transaction {
                TenantScopedSkuMigration(driver, currentTriggerScript()).migrate()
            }
        }
        assertEquals(2L, scalarLong("PRAGMA user_version"))
        assertEquals(oldSchema, scalarString("SELECT sql FROM sqlite_master WHERE type='table' AND name='product_variants'"))
        assertEquals(2L, scalarLong("SELECT COUNT(*) FROM product_variants"))
    }

    @Test
    fun v2MigrationFailurePointsRollbackToTheOriginalSchema() {
        val failurePoints = listOf("after_new_tables", "after_copy", "after_drop_old", "before_commit", "after_user_version")
        for (point in failurePoints) {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            exec("PRAGMA foreign_keys = ON")
            installV2Baseline()
            seedV2Data()

            try {
                StoreDatabase(driver).transaction {
                    TenantScopedSkuMigration(driver, currentTriggerScript(), point.takeUnless { it == "after_user_version" }).migrate()
                    exec("PRAGMA user_version = 3")
                    if (point == "after_user_version") error("Intentional failure after user_version")
                }
                throw AssertionError("Expected rollback at $point")
            } catch (error: IllegalStateException) {
                assertTrue(error.message?.contains("Intentional") == true)
            }

            assertEquals(2L, scalarLong("PRAGMA user_version"))
            assertEquals(1L, scalarLong("SELECT COUNT(*) FROM product_variants"))
            assertEquals(0L, scalarLong("SELECT COUNT(*) FROM pragma_foreign_key_check"))
            assertEquals("ok", scalarString("PRAGMA integrity_check"))
            assertTrue(scalarString("SELECT sql FROM sqlite_master WHERE type='table' AND name='product_variants'")!!.contains("sku TEXT NOT NULL UNIQUE"))
            driver.close()
        }
    }

    private fun installV2Baseline() {
        val schemaFile = generateSequence(java.io.File(System.getProperty("user.dir"))) { it.parentFile }
            .map { java.io.File(it, "shared/data/schema-baselines/v2/canonical_schema.sql") }
            .firstOrNull { it.isFile }
            ?: error("v2 baseline schema not found from ${System.getProperty("user.dir")}")
        val script = schemaFile.readText()
        val ddl = script.substringBefore("CREATE TRIGGER")
        ddl.split(';').map { statement ->
            statement.lineSequence().filterNot { it.trimStart().startsWith("--") }.joinToString("\n").trim()
        }.filter { it.isNotEmpty() }.forEachIndexed { index, statement ->
            try {
                exec(statement)
            } catch (error: Exception) {
                throw AssertionError("v2 DDL statement #$index failed: ${statement.take(120)}", error)
            }
        }
        exec("PRAGMA user_version = 2")
    }

    private fun seedV2Data() {
        exec("INSERT INTO tenants(id, name) VALUES ('T1', 'Tenant 1'), ('T2', 'Tenant 2')")
        exec("INSERT INTO units_of_measure(id, dimension, name_ar, name_fr, symbol) VALUES ('UOM_PIECE', 'COUNT', 'Piece', 'Piece', 'pc')")
        exec("INSERT INTO currencies(code, name, symbol) VALUES ('USD', 'Dollar', '$')")
        exec("INSERT INTO stores(id, tenant_id, code, name) VALUES ('S1', 'T1', 'S1', 'Store 1'), ('S2', 'T2', 'S2', 'Store 2')")
        exec("INSERT INTO products(id, tenant_id, base_unit_id, name, has_variants) VALUES ('P1', 'T1', 'UOM_PIECE', 'Product 1', 1), ('P2', 'T2', 'UOM_PIECE', 'Product 2', 1)")
        exec("INSERT INTO product_variants(id, product_id, sku, variant_name) VALUES ('V1', 'P1', ' abc-01 ', 'Default')")
        exec("INSERT INTO price_lists(id, tenant_id, name, currency_code) VALUES ('PL1', 'T1', 'Default', 'USD')")
        exec("INSERT INTO price_list_items(id, price_list_id, product_id, variant_id, unit_price_ticks) VALUES ('PLI1', 'PL1', 'P1', 'V1', 1250)")
        exec("INSERT INTO warehouses(id, tenant_id, store_id, name) VALUES ('W1', 'T1', 'S1', 'Warehouse 1')")
        exec("INSERT INTO stock_items(id, product_id, variant_id) VALUES ('SI1', 'P1', 'V1')")
        exec("INSERT INTO inventory_balances(warehouse_id, stock_item_id, on_hand_ticks, reserved_ticks, current_moving_avg_cost) VALUES ('W1', 'SI1', 10, 0, 500)")
    }

    private fun currentTriggerScript(): String =
        javaClass.getResourceAsStream("/canonical_triggers.sql")!!.bufferedReader().use { it.readText() }

    private fun assertConstraint(part: String, operation: () -> Unit) {
        try {
            operation()
            throw AssertionError("Expected constraint error containing $part")
        } catch (error: Exception) {
            assertTrue("Expected '$part', got: ${error.message}", error.message?.contains(part) == true)
        }
    }

    private fun exec(sql: String) = driver.execute(null, sql, 0)

    private fun scalarLong(sql: String): Long = driver.executeQuery<Long>(null, sql, {
        it.next()
        QueryResult.Value(it.getLong(0) ?: 0L)
    }, 0).value

    private fun scalarString(sql: String): String? = driver.executeQuery<String?>(null, sql, {
        it.next()
        QueryResult.Value(it.getString(0))
    }, 0).value
}
