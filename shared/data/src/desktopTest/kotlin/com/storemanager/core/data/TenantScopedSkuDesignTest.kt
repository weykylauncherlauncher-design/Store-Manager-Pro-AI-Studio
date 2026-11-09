package com.storemanager.core.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Locale

class TenantScopedSkuDesignTest {

    private lateinit var driver: JdbcSqliteDriver

    @Before
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        exec("PRAGMA foreign_keys = ON")
        exec(
            """
            CREATE TABLE tenants (
                id TEXT PRIMARY KEY
            )
            """.trimIndent()
        )
        exec(
            """
            CREATE TABLE products (
                id TEXT PRIMARY KEY,
                tenant_id TEXT NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
                UNIQUE(id, tenant_id)
            )
            """.trimIndent()
        )
        exec(
            """
            CREATE TABLE product_variants (
                id TEXT PRIMARY KEY,
                tenant_id TEXT NOT NULL,
                product_id TEXT NOT NULL,
                sku TEXT NOT NULL,
                UNIQUE(tenant_id, sku),
                CHECK(sku = trim(sku) AND sku = upper(sku)),
                FOREIGN KEY(product_id, tenant_id)
                    REFERENCES products(id, tenant_id)
                    ON DELETE RESTRICT
            )
            """.trimIndent()
        )
        exec("INSERT INTO tenants(id) VALUES ('T1'), ('T2')")
        exec("INSERT INTO products(id, tenant_id) VALUES ('P1', 'T1'), ('P2', 'T2')")
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun tenantScopedSku_allowsCrossTenant_andRejectsSameTenantDuplicate() {
        insertVariant("V1", "T1", "P1", "ABC-01")
        insertVariant("V2", "T2", "P2", "ABC-01")
        assertEquals(2L, scalarLong("SELECT COUNT(*) FROM product_variants"))

        assertConstraintViolation("UNIQUE constraint failed") {
            insertVariant("V3", "T1", "P1", "ABC-01")
        }
        assertEquals(2L, scalarLong("SELECT COUNT(*) FROM product_variants"))
    }

    @Test
    fun sku_isTrimmedAndUppercasedBeforeStorage() {
        val inputs = listOf("ABC-01", "abc-01", " ABC-01 ")
        val normalizedValues = inputs.map(::normalizeSku)
        assertEquals(listOf("ABC-01", "ABC-01", "ABC-01"), normalizedValues)
        assertTrue(normalizedValues.distinct().single() == "ABC-01")

        val normalized = normalizedValues.last()
        insertVariant("V1", "T1", "P1", normalized)
        assertEquals("ABC-01", scalarString("SELECT sku FROM product_variants WHERE id = 'V1'"))

        assertConstraintViolation("UNIQUE constraint failed") {
            insertVariant("V2", "T1", "P1", normalizeSku("abc-01"))
        }
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM product_variants"))
    }

    @Test
    fun databaseCheck_rejectsNonCanonicalSkuDirectly() {
        assertConstraintViolation("CHECK constraint failed") {
            exec("INSERT INTO product_variants(id, tenant_id, product_id, sku) VALUES ('V1', 'T1', 'P1', ' ABC-01 ')")
        }
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM product_variants"))
        assertDatabaseHealthy()
    }

    @Test
    fun compositeForeignKey_rejectsTenantProductMismatch() {
        exec("BEGIN")
        assertConstraintViolation("FOREIGN KEY constraint failed") {
            exec("INSERT INTO product_variants(id, tenant_id, product_id, sku) VALUES ('V1', 'T2', 'P1', 'ABC-01')")
        }
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM product_variants"))
        exec("ROLLBACK")
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM product_variants"))
        assertDatabaseHealthy()
    }

    @Test
    fun failedInsertTransaction_leavesNoPartialRows() {
        exec("BEGIN")
        insertVariant("V1", "T1", "P1", "ABC-01")
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM product_variants"))

        assertConstraintViolation("UNIQUE constraint failed") {
            insertVariant("V2", "T1", "P1", "ABC-01")
        }
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM product_variants"))
        exec("ROLLBACK")
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM product_variants"))
        assertDatabaseHealthy()
    }

    @Test
    fun databaseChecks_areClean() {
        insertVariant("V1", "T1", "P1", "ABC-01")
        assertDatabaseHealthy()
    }

    private fun assertConstraintViolation(expectedMessagePart: String, operation: () -> Unit) {
        try {
            operation()
            fail("Expected SQLite constraint violation containing '$expectedMessagePart'")
        } catch (error: Exception) {
            assertTrue(
                "Expected '$expectedMessagePart' but got: ${error.message}",
                error.message?.contains(expectedMessagePart) == true
            )
        }
    }

    private fun assertDatabaseHealthy() {
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM pragma_foreign_key_check"))
        assertEquals("ok", scalarString("PRAGMA integrity_check"))
    }

    private fun insertVariant(id: String, tenantId: String, productId: String, sku: String) {
        exec(
            "INSERT INTO product_variants(id, tenant_id, product_id, sku) VALUES ('$id', '$tenantId', '$productId', '$sku')"
        )
    }

    private fun normalizeSku(value: String): String = value.trim().uppercase(Locale.ROOT)

    private fun exec(sql: String) {
        driver.execute(null, sql, 0)
    }

    private fun scalarLong(sql: String): Long = driver.executeQuery<Long>(null, sql, {
        it.next()
        QueryResult.Value(it.getLong(0) ?: 0L)
    }, 0).value

    private fun scalarString(sql: String): String? = driver.executeQuery<String?>(null, sql, {
        it.next()
        QueryResult.Value(it.getString(0))
    }, 0).value
}
