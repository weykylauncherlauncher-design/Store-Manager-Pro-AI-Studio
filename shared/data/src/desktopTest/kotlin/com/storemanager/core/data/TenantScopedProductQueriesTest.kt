package com.storemanager.core.data

import app.cash.sqldelight.db.SqlDriver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class TenantScopedProductQueriesTest {
    private lateinit var tempDbFile: File
    private lateinit var driver: SqlDriver
    private lateinit var database: StoreDatabase
    private val queries get() = database.storeDatabaseQueries

    @Before
    fun setUp() {
        tempDbFile = File.createTempFile("test_tenant_products_", ".db")
        tempDbFile.delete()
        driver = DatabaseDriverFactory(tempDbFile).createDriver()
        database = StoreDatabase(driver)
        queries.insertUnitOfMeasure("UOM_PIECE", "COUNT", "قطعة", "Pièce", "pc", 0)
        queries.insertTenant(TENANT_A, "Product Tenant A", 1)
        queries.insertTenant(TENANT_B, "Product Tenant B", 1)
        driver.execute(
            null,
            "INSERT INTO categories (id, tenant_id, name) VALUES ('CATEGORY_A', '$TENANT_A', 'Category A');",
            0
        )
        driver.execute(
            null,
            "INSERT INTO categories (id, tenant_id, name) VALUES ('CATEGORY_B', '$TENANT_B', 'Category B');",
            0
        )
        createProduct("PRODUCT_A", TENANT_A, name = "Alpha Product", code = "A-CODE", barcode = "A-BAR")
        createProduct(
            "PRODUCT_B",
            TENANT_B,
            name = "Beta Product",
            code = "B-CODE",
            barcode = "B-BAR",
            categoryId = "CATEGORY_B"
        )
    }

    @After
    fun tearDown() {
        try {
            driver.close()
        } catch (_: Throwable) {
        }
        tempDbFile.delete()
        File("${tempDbFile.absolutePath}-wal").delete()
        File("${tempDbFile.absolutePath}-shm").delete()
        File("${tempDbFile.parentFile.absolutePath}/${tempDbFile.name}.init.lock").delete()
    }

    @Test
    fun listsAndFetchesProductsOnlyWithinRequestedTenant() {
        assertEquals(
            listOf("PRODUCT_A"),
            queries.selectActiveProductsByTenant(TENANT_A).executeAsList().map { it.id }
        )
        assertEquals(
            listOf("PRODUCT_B"),
            queries.selectActiveProductsByTenant(TENANT_B).executeAsList().map { it.id }
        )
        assertNull(queries.selectProductByTenantAndId("PRODUCT_B", TENANT_A).executeAsOneOrNull())
        assertEquals("PRODUCT_B", queries.selectProductByTenantAndId("PRODUCT_B", TENANT_B).executeAsOne().id)
    }

    @Test
    fun searchMatchesNameCodeAndBarcodeWithoutCrossTenantResults() {
        assertEquals(
            listOf("PRODUCT_A"),
            queries.searchProductsByTenant(TENANT_A, 0, "Alpha%", "no-exact-barcode")
                .executeAsList().map { it.id }
        )
        assertEquals(
            listOf("PRODUCT_A"),
            queries.searchProductsByTenant(TENANT_A, 0, "A-CODE%", "no-exact-barcode")
                .executeAsList().map { it.id }
        )
        assertEquals(
            listOf("PRODUCT_A"),
            queries.searchProductsByTenant(TENANT_A, 0, "no-prefix-match%", "A-BAR")
                .executeAsList().map { it.id }
        )
        assertTrue(
            queries.searchProductsByTenant(TENANT_A, 0, "Beta%", "B-BAR")
                .executeAsList().isEmpty()
        )
    }

    @Test
    fun foreignTenantCannotUpdateOrDeactivateProducts() {
        val before = queries.selectProductByTenantAndId("PRODUCT_B", TENANT_B).executeAsOne()

        queries.updateProductForTenant(
            categoryId = null,
            baseUnitId = "UOM_PIECE",
            name = "Illicit update",
            code = "ILLEGAL",
            barcode = "ILLEGAL-BAR",
            trackInventory = 0,
            id = "PRODUCT_B",
            tenantId = TENANT_A
        )
        queries.deactivateProductForTenant("PRODUCT_B", TENANT_A)

        val after = queries.selectProductByTenantAndId("PRODUCT_B", TENANT_B).executeAsOne()
        assertEquals(before.name, after.name)
        assertEquals(before.code, after.code)
        assertEquals(before.barcode, after.barcode)
        assertEquals(before.is_active, after.is_active)
        assertEquals(1L, after.is_active)
    }

    @Test
    fun canCreateUpdateDeactivateAndReactivateSimpleProductWithoutDeletingIt() {
        createProduct("PRODUCT_NEW", TENANT_A, name = "New Product", code = "NEW-CODE")
        var product = queries.selectProductByTenantAndId("PRODUCT_NEW", TENANT_A).executeAsOne()
        assertEquals(0L, product.has_variants)
        assertEquals("UOM_PIECE", product.base_unit_id)
        assertEquals("CATEGORY_A", product.category_id)
        assertEquals(
            1,
            queries.selectAllStockItems().executeAsList().count { it.product_id == "PRODUCT_NEW" }
        )

        queries.updateProductForTenant(
            categoryId = "CATEGORY_A",
            baseUnitId = "UOM_PIECE",
            name = "Renamed Product",
            code = "RENAMED-CODE",
            barcode = "NEW-BAR",
            trackInventory = 0,
            id = "PRODUCT_NEW",
            tenantId = TENANT_A
        )
        product = queries.selectProductByTenantAndId("PRODUCT_NEW", TENANT_A).executeAsOne()
        assertEquals("Renamed Product", product.name)
        assertEquals("RENAMED-CODE", product.code)
        assertEquals("NEW-BAR", product.barcode)
        assertEquals(0L, product.track_inventory)

        queries.deactivateProductForTenant("PRODUCT_NEW", TENANT_A)
        assertTrue(queries.selectActiveProductsByTenant(TENANT_A).executeAsList().none { it.id == "PRODUCT_NEW" })
        assertEquals(
            "PRODUCT_NEW",
            queries.selectProductsByTenant(TENANT_A, 1).executeAsList().single { it.id == "PRODUCT_NEW" }.id
        )
        assertTrue(
            queries.searchProductsByTenant(TENANT_A, 0, "Renamed%", "NEW-BAR")
                .executeAsList().none { it.id == "PRODUCT_NEW" }
        )
        assertEquals(
            "PRODUCT_NEW",
            queries.searchProductsByTenant(TENANT_A, 1, "Renamed%", "NEW-BAR")
                .executeAsList().single { it.id == "PRODUCT_NEW" }.id
        )
        assertEquals(0L, queries.selectProductByTenantAndId("PRODUCT_NEW", TENANT_A).executeAsOne().is_active)

        queries.reactivateProductForTenant("PRODUCT_NEW", TENANT_A)
        assertEquals(1L, queries.selectProductByTenantAndId("PRODUCT_NEW", TENANT_A).executeAsOne().is_active)
        assertNotNull(queries.selectProductByTenantAndId("PRODUCT_NEW", TENANT_A).executeAsOneOrNull())
    }

    @Test
    fun databaseStillEnforcesBarcodeUnitAndCategoryConstraints() {
        assertThrows(Exception::class.java) {
            createProduct(
                "PRODUCT_DUPLICATE_BARCODE",
                TENANT_A,
                name = "Duplicate Barcode",
                code = "DUP-BAR-CODE",
                barcode = "A-BAR"
            )
        }
        assertNull(queries.selectProductByTenantAndId("PRODUCT_DUPLICATE_BARCODE", TENANT_A).executeAsOneOrNull())

        assertThrows(Exception::class.java) {
            createProduct(
                "PRODUCT_INVALID_UNIT",
                TENANT_A,
                name = "Invalid Unit",
                code = "INVALID-UNIT",
                baseUnitId = "MISSING_UNIT"
            )
        }
        assertNull(queries.selectProductByTenantAndId("PRODUCT_INVALID_UNIT", TENANT_A).executeAsOneOrNull())

        assertThrows(Exception::class.java) {
            createProduct(
                "PRODUCT_FOREIGN_CATEGORY",
                TENANT_A,
                name = "Foreign Category",
                code = "FOREIGN-CATEGORY",
                categoryId = "CATEGORY_B"
            )
        }
        assertNull(queries.selectProductByTenantAndId("PRODUCT_FOREIGN_CATEGORY", TENANT_A).executeAsOneOrNull())
    }

    private fun createProduct(
        id: String,
        tenantId: String,
        name: String,
        code: String,
        barcode: String? = null,
        categoryId: String? = "CATEGORY_A",
        baseUnitId: String = "UOM_PIECE"
    ) {
        queries.insertProduct(
            id = id,
            tenantId = tenantId,
            categoryId = categoryId,
            baseUnitId = baseUnitId,
            name = name,
            code = code,
            barcode = barcode,
            trackInventory = 1
        )
    }

    private companion object {
        const val TENANT_A = "TENANT_PRODUCTS_A"
        const val TENANT_B = "TENANT_PRODUCTS_B"
    }
}
