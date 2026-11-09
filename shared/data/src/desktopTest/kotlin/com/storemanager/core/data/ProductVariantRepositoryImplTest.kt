package com.storemanager.core.data

import app.cash.sqldelight.db.SqlDriver
import com.storemanager.core.domain.model.CreateProductVariant
import com.storemanager.core.domain.model.UpdateProductVariant
import com.storemanager.core.domain.model.VariantAttributes
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ProductVariantRepositoryImplTest {
    private lateinit var databaseFile: File
    private lateinit var driver: SqlDriver
    private lateinit var database: StoreDatabase
    private lateinit var repository: ProductVariantRepositoryImpl
    private val queries get() = database.storeDatabaseQueries

    @Before
    fun setUp() {
        databaseFile = File.createTempFile("test_product_variant_repository_", ".db")
        databaseFile.delete()
        driver = DatabaseDriverFactory(databaseFile).createDriver()
        database = StoreDatabase(driver)
        repository = ProductVariantRepositoryImpl(database)

        queries.insertUnitOfMeasure("UOM_PIECE", "COUNT", "قطعة", "Pièce", "pc", 0)
        queries.insertTenant(TENANT_A, "Variant Tenant A", 1)
        queries.insertTenant(TENANT_B, "Variant Tenant B", 1)
        insertProduct(PRODUCT_A, TENANT_A, "Product A", hasVariants = true)
        insertProduct(PRODUCT_B, TENANT_B, "Product B", hasVariants = true)
        insertProduct(SIMPLE_PRODUCT, TENANT_A, "Simple Product", hasVariants = false)
    }

    @After
    fun tearDown() {
        try {
            driver.close()
        } catch (_: Throwable) {
        }
        databaseFile.delete()
        File("${databaseFile.absolutePath}-wal").delete()
        File("${databaseFile.absolutePath}-shm").delete()
        File("${databaseFile.parentFile.absolutePath}/${databaseFile.name}.init.lock").delete()
    }

    @Test
    fun createsVariantAndTriggerCreatesExactlyOneStockItem() = runBlocking {
        val created = repository.createVariant(
            TENANT_A,
            PRODUCT_A,
            CreateProductVariant(
                sku = " abc-01 ",
                barcode = "BAR-A1",
                name = "Red",
                attributes = VariantAttributes(mapOf("color" to "red"))
            )
        )

        assertEquals(PRODUCT_A, created.productId)
        assertEquals("ABC-01", created.sku)
        assertEquals(mapOf("color" to "red"), created.attributes.values)
        assertTrue(created.isActive)
        assertEquals(1L, countStockItems(PRODUCT_A, created.id))
        assertEquals(1L, countVariants(TENANT_A, PRODUCT_A))
    }

    @Test
    fun tenantIsolationHidesForeignProductAndVariant() = runBlocking {
        val created = repository.createVariant(
            TENANT_B,
            PRODUCT_B,
            CreateProductVariant("B-01", name = "Tenant B")
        )

        assertNull(repository.getVariant(TENANT_A, created.id))
        assertTrue(repository.getVariants(TENANT_A, PRODUCT_A).isEmpty())
        assertFalse(repository.updateVariant(TENANT_A, created.id, UpdateProductVariant("B-02", null, "No access", VariantAttributes(emptyMap()))))
        assertFalse(repository.deactivateVariant(TENANT_A, created.id))
        assertFalse(repository.reactivateVariant(TENANT_A, created.id))
    }

    @Test
    fun rejectsForeignParentAndSimpleProduct() = runBlocking {
        assertThrows(ProductVariantTenantException::class.java) {
            runBlocking {
                repository.createVariant(TENANT_A, PRODUCT_B, CreateProductVariant("CROSS-01", name = "Cross"))
            }
        }
        assertThrows(ProductVariantModeException::class.java) {
            runBlocking {
                repository.createVariant(TENANT_A, SIMPLE_PRODUCT, CreateProductVariant("SIMPLE-01", name = "Invalid"))
            }
        }
        assertEquals(0L, countVariants(TENANT_A, PRODUCT_A))
    }

    @Test
    fun allowsSameSkuAcrossTenantsButRejectsDuplicateWithinTenant() = runBlocking {
        repository.createVariant(TENANT_A, PRODUCT_A, CreateProductVariant("SHARED-01", name = "A"))
        repository.createVariant(TENANT_B, PRODUCT_B, CreateProductVariant("SHARED-01", name = "B"))

        assertThrows(ProductVariantConstraintException::class.java) {
            runBlocking {
                repository.createVariant(TENANT_A, PRODUCT_A, CreateProductVariant(" shared-01 ", name = "Duplicate"))
            }
        }
        assertEquals(1L, countVariants(TENANT_A, PRODUCT_A))
        assertEquals(1L, countVariants(TENANT_B, PRODUCT_B))
    }

    @Test
    fun updatesVariantWithoutChangingParentAndPreservesAttributes() = runBlocking {
        val created = repository.createVariant(
            TENANT_A,
            PRODUCT_A,
            CreateProductVariant("OLD-01", name = "Old", attributes = VariantAttributes(mapOf("size" to "S")))
        )

        assertTrue(
            repository.updateVariant(
                TENANT_A,
                created.id,
                UpdateProductVariant(
                    sku = " new-01 ",
                    barcode = "BAR-NEW",
                    name = "Updated",
                    attributes = VariantAttributes(mapOf("size" to "M", "color" to "blue"))
                )
            )
        )

        val updated = repository.getVariant(TENANT_A, created.id)!!
        assertEquals(PRODUCT_A, updated.productId)
        assertEquals("NEW-01", updated.sku)
        assertEquals("BAR-NEW", updated.barcode)
        assertEquals("Updated", updated.name)
        assertEquals(mapOf("size" to "M", "color" to "blue"), updated.attributes.values)
        assertEquals(1L, countStockItems(PRODUCT_A, created.id))
    }

    @Test
    fun deactivatesAndReactivatesWithoutDeletingVariantOrStock() = runBlocking {
        val created = repository.createVariant(TENANT_A, PRODUCT_A, CreateProductVariant("STATE-01", name = "State"))

        assertTrue(repository.deactivateVariant(TENANT_A, created.id))
        assertFalse(repository.getVariants(TENANT_A, PRODUCT_A).any { it.id == created.id })
        assertFalse(repository.getVariant(TENANT_A, created.id)!!.isActive)
        assertEquals(1L, countVariants(TENANT_A, PRODUCT_A))

        assertTrue(repository.reactivateVariant(TENANT_A, created.id))
        assertTrue(repository.getVariant(TENANT_A, created.id)!!.isActive)
        assertEquals(1, repository.getVariants(TENANT_A, PRODUCT_A).count { it.id == created.id })
        assertEquals(1L, countStockItems(PRODUCT_A, created.id))
    }

    @Test
    fun failedDuplicateCreationLeavesNoPartialVariantOrStockItem() = runBlocking {
        repository.createVariant(TENANT_A, PRODUCT_A, CreateProductVariant("ROLLBACK-01", name = "Existing"))

        assertThrows(ProductVariantConstraintException::class.java) {
            runBlocking {
                repository.createVariant(TENANT_A, PRODUCT_A, CreateProductVariant(" rollback-01 ", name = "Duplicate"))
            }
        }

        assertEquals(1L, countVariants(TENANT_A, PRODUCT_A))
        assertEquals(1L, countStockItems(PRODUCT_A))
    }

    private fun insertProduct(id: String, tenantId: String, name: String, hasVariants: Boolean) {
        queries.insertProduct(
            id = id,
            tenantId = tenantId,
            categoryId = null,
            baseUnitId = "UOM_PIECE",
            name = name,
            code = null,
            barcode = null,
            trackInventory = 1
        )
        if (hasVariants) {
            driver.execute(null, "DELETE FROM stock_items WHERE product_id = '$id'", 0)
            driver.execute(null, "UPDATE products SET has_variants = 1 WHERE id = '$id'", 0)
        }
    }

    private fun countVariants(tenantId: String, productId: String): Long =
        queries.selectVariantsByTenantAndProduct(tenantId, productId, 1).executeAsList().size.toLong()

    private fun countStockItems(productId: String, variantId: String? = null): Long =
        if (variantId == null) {
            driver.executeQuery<Long>(null, "SELECT COUNT(*) FROM stock_items WHERE product_id = '$productId'", {
                it.next()
                app.cash.sqldelight.db.QueryResult.Value(it.getLong(0) ?: 0L)
            }, 0).value
        } else {
            driver.executeQuery<Long>(null, "SELECT COUNT(*) FROM stock_items WHERE product_id = '$productId' AND variant_id = '$variantId'", {
                it.next()
                app.cash.sqldelight.db.QueryResult.Value(it.getLong(0) ?: 0L)
            }, 0).value
        }

    private companion object {
        const val TENANT_A = "TENANT_A"
        const val TENANT_B = "TENANT_B"
        const val PRODUCT_A = "PRODUCT_A"
        const val PRODUCT_B = "PRODUCT_B"
        const val SIMPLE_PRODUCT = "SIMPLE_PRODUCT"
    }
}
