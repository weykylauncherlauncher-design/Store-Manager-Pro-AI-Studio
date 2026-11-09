package com.storemanager.core.data

import app.cash.sqldelight.db.SqlDriver
import com.storemanager.core.domain.model.CreateProduct
import com.storemanager.core.domain.model.CreateProductVariant
import com.storemanager.core.domain.model.Product
import com.storemanager.core.domain.model.UpdateProduct
import com.storemanager.core.domain.model.VariantAttributes
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ProductRepositoryImplTest {
    private lateinit var tempDbFile: File
    private lateinit var driver: SqlDriver
    private lateinit var database: StoreDatabase
    private lateinit var repository: ProductRepositoryImpl
    private val queries get() = database.storeDatabaseQueries

    @Before
    fun setUp() {
        tempDbFile = File.createTempFile("test_product_repository_", ".db")
        tempDbFile.delete()
        driver = DatabaseDriverFactory(tempDbFile).createDriver()
        database = StoreDatabase(driver)
        repository = ProductRepositoryImpl(database)

        queries.insertUnitOfMeasure("UOM_PIECE", "COUNT", "قطعة", "Pièce", "pc", 0)
        queries.insertUnitOfMeasure("UOM_KG", "WEIGHT", "كيلوغرام", "Kilogramme", "kg", 1)
        queries.insertTenant(TENANT_A, "Repository Tenant A", 1)
        queries.insertTenant(TENANT_B, "Repository Tenant B", 1)
        insertCategory(CATEGORY_A, TENANT_A)
        insertCategory(CATEGORY_B, TENANT_B)
        queries.insertProduct(
            id = PRODUCT_A,
            tenantId = TENANT_A,
            categoryId = CATEGORY_A,
            baseUnitId = "UOM_PIECE",
            name = "Alpha Product",
            code = "A-CODE",
            barcode = "A-BAR",
            trackInventory = 1
        )
        queries.insertProduct(
            id = PRODUCT_B,
            tenantId = TENANT_B,
            categoryId = CATEGORY_B,
            baseUnitId = "UOM_KG",
            name = "Beta Product",
            code = "B-CODE",
            barcode = "B-BAR",
            trackInventory = 0
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
    fun mapsRowsAndScopesListAndGetByTenant() = runBlocking {
        val product = repository.getProduct(TENANT_A, PRODUCT_A)

        assertNotNull(product)
        assertEquals(
            Product(
                id = PRODUCT_A,
                tenantId = TENANT_A,
                name = "Alpha Product",
                categoryId = CATEGORY_A,
                baseUnitId = "UOM_PIECE",
                code = "A-CODE",
                barcode = "A-BAR",
                isActive = true,
                createdAt = queries.selectProductByTenantAndId(PRODUCT_A, TENANT_A).executeAsOne().created_at
            ),
            product
        )
        assertTrue(product!!.createdAt.isNotBlank())
        assertEquals(listOf(PRODUCT_A), repository.getProducts(TENANT_A).map { it.id })
        assertEquals(listOf(PRODUCT_B), repository.getProducts(TENANT_B).map { it.id })
        assertNull(repository.getProduct(TENANT_A, PRODUCT_B))
    }

    @Test
    fun searchesByNameCodeAndExactBarcodeWithoutCrossTenantResults() = runBlocking {
        assertEquals(listOf(PRODUCT_A), repository.searchProducts(TENANT_A, "Alpha").map { it.id })
        assertEquals(listOf(PRODUCT_A), repository.searchProducts(TENANT_A, "A-CODE").map { it.id })
        assertEquals(listOf(PRODUCT_A), repository.searchProducts(TENANT_A, "A-BAR").map { it.id })
        assertTrue(repository.searchProducts(TENANT_A, "Beta").isEmpty())
    }

    @Test
    fun createsSimpleProductAndPersistsDomainFields() = runBlocking {
        val created = repository.createProduct(
            TENANT_A,
            CreateProduct(
                name = "Created Product",
                baseUnitId = "UOM_PIECE",
                categoryId = CATEGORY_A,
                code = "CREATED-CODE",
                barcode = "CREATED-BAR"
            )
        )

        assertTrue(created.id.startsWith("PRODUCT_"))
        assertEquals(TENANT_A, created.tenantId)
        assertEquals("Created Product", created.name)
        assertEquals(CATEGORY_A, created.categoryId)
        assertEquals("UOM_PIECE", created.baseUnitId)
        assertEquals("CREATED-CODE", created.code)
        assertEquals("CREATED-BAR", created.barcode)
        assertTrue(created.isActive)
        assertTrue(created.createdAt.isNotBlank())
        assertEquals(0L, queries.selectProductByTenantAndId(created.id, TENANT_A).executeAsOne().has_variants)
        assertEquals(1, queries.selectAllStockItems().executeAsList().count { it.product_id == created.id })
    }

    @Test
    fun createsProductWithVariantsAtomicallyAndCreatesOnlyVariantStockItems() = runBlocking {
        val created = repository.createProductWithVariants(
            TENANT_A,
            CreateProduct(
                name = "Variable Product",
                baseUnitId = "UOM_PIECE",
                code = "VARIABLE-CODE",
                barcode = "VARIABLE-BAR"
            ),
            listOf(
                CreateProductVariant(
                    sku = " red-01 ",
                    barcode = "VARIABLE-RED",
                    name = "Red",
                    attributes = VariantAttributes(mapOf("color" to "red"))
                ),
                CreateProductVariant(
                    sku = "BLUE-01",
                    barcode = "VARIABLE-BLUE",
                    name = "Blue",
                    attributes = VariantAttributes(mapOf("color" to "blue"))
                )
            )
        )

        assertEquals(TENANT_A, created.product.tenantId)
        assertEquals("VARIABLE-CODE", created.product.code)
        assertEquals("VARIABLE-BAR", created.product.barcode)
        assertEquals(1L, queries.selectProductByTenantAndId(created.product.id, TENANT_A).executeAsOne().has_variants)
        assertEquals(listOf("BLUE-01", "RED-01"), created.variants.map { it.sku }.sorted())
        assertTrue(created.variants.all { it.productId == created.product.id })
        assertEquals(
            created.variants.map { it.id }.toSet(),
            queries.selectVariantsByTenantAndProduct(TENANT_A, created.product.id, 1)
                .executeAsList()
                .map { it.id }
                .toSet()
        )
        assertTrue(queries.selectVariantsByTenantAndProduct(TENANT_B, created.product.id, 1).executeAsList().isEmpty())
        assertEquals(2L, countStockItemsForProduct(created.product.id))
        assertEquals(0L, countSimpleStockItemsForProduct(created.product.id))
        created.variants.forEach { variant ->
            assertEquals(1L, countStockItemsForVariant(created.product.id, variant.id))
        }
    }

    @Test
    fun rejectsEmptyVariantListBeforeWritingAnything() = runBlocking {
        val productsBefore = repository.getProducts(TENANT_A, includeInactive = true).size
        val stockItemsBefore = queries.selectAllStockItems().executeAsList().size

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.createProductWithVariants(
                    TENANT_A,
                    CreateProduct("Empty Variants", "UOM_PIECE"),
                    emptyList()
                )
            }
        }

        assertEquals(productsBefore, repository.getProducts(TENANT_A, includeInactive = true).size)
        assertEquals(stockItemsBefore, queries.selectAllStockItems().executeAsList().size)
    }

    @Test
    fun duplicateSkuInSecondVariantRollsBackProductVariantsAndTriggerStockItems() = runBlocking {
        val productsBefore = repository.getProducts(TENANT_A, includeInactive = true).size
        val stockItemsBefore = queries.selectAllStockItems().executeAsList().size

        assertThrows(ProductVariantConstraintException::class.java) {
            runBlocking {
                repository.createProductWithVariants(
                    TENANT_A,
                    CreateProduct("Atomic Rollback", "UOM_PIECE", code = "ROLLBACK-CODE"),
                    listOf(
                        CreateProductVariant("rollback-01", name = "First"),
                        CreateProductVariant(" ROLLBACK-01 ", name = "Duplicate")
                    )
                )
            }
        }

        assertEquals(productsBefore, repository.getProducts(TENANT_A, includeInactive = true).size)
        assertEquals(stockItemsBefore, queries.selectAllStockItems().executeAsList().size)
        assertEquals(0L, countProductsByCode("ROLLBACK-CODE"))
        assertEquals(0L, countVariantsBySku("ROLLBACK-01"))
    }

    @Test
    fun updatesProductFieldsWithoutChangingItsUnit() = runBlocking {
        assertTrue(
            repository.updateProduct(
                TENANT_A,
                PRODUCT_A,
                UpdateProduct(
                    name = "Updated Product",
                    categoryId = null,
                    code = "UPDATED-CODE",
                    barcode = "UPDATED-BAR"
                )
            )
        )

        val updated = repository.getProduct(TENANT_A, PRODUCT_A)!!
        assertEquals("Updated Product", updated.name)
        assertNull(updated.categoryId)
        assertEquals("UOM_PIECE", updated.baseUnitId)
        assertEquals("UPDATED-CODE", updated.code)
        assertEquals("UPDATED-BAR", updated.barcode)
    }

    @Test
    fun deactivatesAndReactivatesWithoutDeletingTheProduct() = runBlocking {
        assertTrue(repository.deactivateProduct(TENANT_A, PRODUCT_A))
        assertFalse(repository.getProduct(TENANT_A, PRODUCT_A)!!.isActive)
        assertTrue(repository.getProducts(TENANT_A).isEmpty())
        assertTrue(repository.searchProducts(TENANT_A, "Alpha").isEmpty())
        assertEquals(listOf(PRODUCT_A), repository.getProducts(TENANT_A, includeInactive = true).map { it.id })
        assertEquals(
            listOf(PRODUCT_A),
            repository.searchProducts(TENANT_A, "Alpha", includeInactive = true).map { it.id }
        )

        assertTrue(repository.reactivateProduct(TENANT_A, PRODUCT_A))
        assertTrue(repository.getProduct(TENANT_A, PRODUCT_A)!!.isActive)
        assertEquals(listOf(PRODUCT_A), repository.getProducts(TENANT_A).map { it.id })
        assertEquals(1, queries.selectProductsByTenant(TENANT_A, 1).executeAsList().count { it.id == PRODUCT_A })
    }

    @Test
    fun foreignTenantCannotUpdateDeactivateOrReactivateProduct() = runBlocking {
        val before = repository.getProduct(TENANT_B, PRODUCT_B)!!

        assertFalse(
            repository.updateProduct(
                TENANT_A,
                PRODUCT_B,
                UpdateProduct("Unauthorized Change", null, "BAD-CODE", "BAD-BAR")
            )
        )
        assertFalse(repository.deactivateProduct(TENANT_A, PRODUCT_B))
        assertFalse(repository.reactivateProduct(TENANT_A, PRODUCT_B))

        assertEquals(before, repository.getProduct(TENANT_B, PRODUCT_B))
        assertEquals(listOf(PRODUCT_A), repository.getProducts(TENANT_A).map { it.id })
        assertEquals(listOf(PRODUCT_B), repository.getProducts(TENANT_B).map { it.id })
    }

    @Test
    fun databaseConstraintFailuresPropagateAndDoNotCreatePartialProducts() = runBlocking {
        assertThrows(Exception::class.java) {
            runBlocking {
                repository.createProduct(
                    TENANT_A,
                    CreateProduct("Duplicate Barcode", "UOM_PIECE", barcode = "A-BAR")
                )
            }
        }
        assertThrows(Exception::class.java) {
            runBlocking {
                repository.createProduct(
                    TENANT_A,
                    CreateProduct("Missing Unit", "MISSING_UNIT")
                )
            }
        }
        assertThrows(Exception::class.java) {
            runBlocking {
                repository.createProduct(
                    TENANT_A,
                    CreateProduct("Foreign Category", "UOM_PIECE", categoryId = CATEGORY_B)
                )
            }
        }
        assertEquals(listOf(PRODUCT_A), repository.getProducts(TENANT_A).map { it.id })
        assertEquals(listOf(PRODUCT_B), repository.getProducts(TENANT_B).map { it.id })
    }

    @Test
    fun categoryAssignmentsAreTenantScopedAndNullCategoriesAreAllowed() = runBlocking {
        val sameTenantProduct = repository.createProduct(
            TENANT_A,
            CreateProduct(
                name = "Same Tenant Category",
                baseUnitId = "UOM_PIECE",
                categoryId = CATEGORY_A
            )
        )
        assertEquals(CATEGORY_A, sameTenantProduct.categoryId)

        val productsBeforeRejectedCreate = repository.getProducts(TENANT_A, includeInactive = true)
        val stockItemsBeforeRejectedCreate = queries.selectAllStockItems().executeAsList().size
        assertThrows(Exception::class.java) {
            runBlocking {
                repository.createProduct(
                    TENANT_A,
                    CreateProduct(
                        name = "Foreign Category Create",
                        baseUnitId = "UOM_PIECE",
                        categoryId = CATEGORY_B
                    )
                )
            }
        }
        assertEquals(
            productsBeforeRejectedCreate,
            repository.getProducts(TENANT_A, includeInactive = true)
        )
        assertEquals(stockItemsBeforeRejectedCreate, queries.selectAllStockItems().executeAsList().size)

        val originalProduct = repository.getProduct(TENANT_A, PRODUCT_A)!!
        assertThrows(Exception::class.java) {
            runBlocking {
                repository.updateProduct(
                    TENANT_A,
                    PRODUCT_A,
                    UpdateProduct(
                        name = "Must Not Persist",
                        categoryId = CATEGORY_B,
                        code = "MUST-NOT-PERSIST",
                        barcode = "MUST-NOT-PERSIST"
                    )
                )
            }
        }
        assertEquals(originalProduct, repository.getProduct(TENANT_A, PRODUCT_A))

        assertTrue(
            repository.updateProduct(
                TENANT_A,
                PRODUCT_A,
                UpdateProduct(
                    name = "Same Tenant Category Update",
                    categoryId = CATEGORY_A,
                    code = originalProduct.code,
                    barcode = originalProduct.barcode
                )
            )
        )
        assertEquals(CATEGORY_A, repository.getProduct(TENANT_A, PRODUCT_A)!!.categoryId)

        val uncategorizedProduct = repository.createProduct(
            TENANT_A,
            CreateProduct("Uncategorized Product", "UOM_PIECE", categoryId = null)
        )
        assertNull(uncategorizedProduct.categoryId)
        assertTrue(
            repository.updateProduct(
                TENANT_A,
                PRODUCT_A,
                UpdateProduct(
                    name = "Uncategorized Update",
                    categoryId = null,
                    code = originalProduct.code,
                    barcode = originalProduct.barcode
                )
            )
        )
        assertNull(repository.getProduct(TENANT_A, PRODUCT_A)!!.categoryId)
    }

    private fun insertCategory(id: String, tenantId: String) {
        driver.execute(
            null,
            "INSERT INTO categories (id, tenant_id, name) VALUES ('$id', '$tenantId', '$id');",
            0
        )
    }

    private fun countProductsByCode(code: String): Long = driver.executeQuery<Long>(
        null,
        "SELECT COUNT(*) FROM products WHERE code = '$code'",
        {
            it.next()
            app.cash.sqldelight.db.QueryResult.Value(it.getLong(0) ?: 0L)
        },
        0
    ).value

    private fun countVariantsBySku(sku: String): Long = driver.executeQuery<Long>(
        null,
        "SELECT COUNT(*) FROM product_variants WHERE sku = '$sku'",
        {
            it.next()
            app.cash.sqldelight.db.QueryResult.Value(it.getLong(0) ?: 0L)
        },
        0
    ).value

    private fun countStockItemsForProduct(productId: String): Long = driver.executeQuery<Long>(
        null,
        "SELECT COUNT(*) FROM stock_items WHERE product_id = '$productId'",
        {
            it.next()
            app.cash.sqldelight.db.QueryResult.Value(it.getLong(0) ?: 0L)
        },
        0
    ).value

    private fun countSimpleStockItemsForProduct(productId: String): Long = driver.executeQuery<Long>(
        null,
        "SELECT COUNT(*) FROM stock_items WHERE product_id = '$productId' AND variant_id IS NULL",
        {
            it.next()
            app.cash.sqldelight.db.QueryResult.Value(it.getLong(0) ?: 0L)
        },
        0
    ).value

    private fun countStockItemsForVariant(productId: String, variantId: String): Long = driver.executeQuery<Long>(
        null,
        "SELECT COUNT(*) FROM stock_items WHERE product_id = '$productId' AND variant_id = '$variantId'",
        {
            it.next()
            app.cash.sqldelight.db.QueryResult.Value(it.getLong(0) ?: 0L)
        },
        0
    ).value

    private companion object {
        const val TENANT_A = "TENANT_REPOSITORY_A"
        const val TENANT_B = "TENANT_REPOSITORY_B"
        const val CATEGORY_A = "CATEGORY_REPOSITORY_A"
        const val CATEGORY_B = "CATEGORY_REPOSITORY_B"
        const val PRODUCT_A = "PRODUCT_REPOSITORY_A"
        const val PRODUCT_B = "PRODUCT_REPOSITORY_B"
    }
}
