package com.storemanager.core.data

import app.cash.sqldelight.db.SqlDriver
import com.storemanager.core.domain.model.CreateProductCategory
import com.storemanager.core.domain.model.ProductCategory
import com.storemanager.core.domain.model.UpdateProductCategory
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

class ProductCategoryRepositoryImplTest {
    private lateinit var tempDbFile: File
    private lateinit var driver: SqlDriver
    private lateinit var database: StoreDatabase
    private lateinit var repository: ProductCategoryRepositoryImpl

    @Before
    fun setUp() {
        tempDbFile = File.createTempFile("test_product_category_repository_", ".db")
        tempDbFile.delete()
        driver = DatabaseDriverFactory(tempDbFile).createDriver()
        database = StoreDatabase(driver)
        repository = ProductCategoryRepositoryImpl(database)

        database.storeDatabaseQueries.insertUnitOfMeasure("UOM_PIECE", "COUNT", "قطعة", "Pièce", "pc", 0)
        database.storeDatabaseQueries.insertTenant(TENANT_A, "Category Tenant A", 1)
        database.storeDatabaseQueries.insertTenant(TENANT_B, "Category Tenant B", 1)
        insertCategory(CATEGORY_A, TENANT_A, "A Category", "A-CODE")
        insertCategory(CATEGORY_CHILD, TENANT_A, "A Child", "A-CHILD", parentId = CATEGORY_A)
        insertCategory(CATEGORY_B, TENANT_B, "B Category", "B-CODE")
        insertCategory(CATEGORY_INACTIVE, TENANT_A, "Inactive Category", null, isActive = false)
        database.storeDatabaseQueries.insertProduct(
            id = PRODUCT_A,
            tenantId = TENANT_A,
            categoryId = CATEGORY_A,
            baseUnitId = "UOM_PIECE",
            name = "Linked Product",
            code = "LINKED-PRODUCT",
            barcode = "LINKED-BARCODE",
            trackInventory = 1
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
    fun readsOnlyRequestedTenantAndMapsSchemaFields() = runBlocking {
        assertEquals(
            listOf(
                ProductCategory(
                    id = CATEGORY_A,
                    tenantId = TENANT_A,
                    parentId = null,
                    name = "A Category",
                    code = "A-CODE",
                    isActive = true
                ),
                ProductCategory(
                    id = CATEGORY_CHILD,
                    tenantId = TENANT_A,
                    parentId = CATEGORY_A,
                    name = "A Child",
                    code = "A-CHILD",
                    isActive = true
                )
            ),
            repository.getCategories(TENANT_A)
        )
        assertEquals(listOf(CATEGORY_B), repository.getCategories(TENANT_B).map { it.id })
        assertTrue(repository.getCategories("TENANT_EMPTY").isEmpty())
    }

    @Test
    fun inactiveCategoriesAreExcludedByDefaultAndCanBeIncluded() = runBlocking {
        assertEquals(
            listOf(CATEGORY_A, CATEGORY_CHILD),
            repository.getCategories(TENANT_A).map { it.id }
        )
        assertEquals(
            listOf(CATEGORY_A, CATEGORY_CHILD, CATEGORY_INACTIVE),
            repository.getCategories(TENANT_A, includeInactive = true).map { it.id }
        )
        assertTrue(repository.getCategories(TENANT_A, includeInactive = true).last().isActive.not())
    }

    @Test
    fun createsCategoryWithOptionalCodeAndParent() = runBlocking {
        val created = repository.createCategory(
            TENANT_A,
            CreateProductCategory(
                name = "New Category",
                code = "NEW-CODE",
                parentId = CATEGORY_A
            )
        )

        assertTrue(created.id.startsWith("CATEGORY_"))
        assertEquals(TENANT_A, created.tenantId)
        assertEquals("New Category", created.name)
        assertEquals("NEW-CODE", created.code)
        assertEquals(CATEGORY_A, created.parentId)
        assertTrue(created.isActive)

        val duplicateSchemaValues = repository.createCategory(
            TENANT_A,
            CreateProductCategory(name = "A Category", code = "A-CODE")
        )
        assertEquals("A Category", duplicateSchemaValues.name)
        assertEquals("A-CODE", duplicateSchemaValues.code)
        assertNull(repository.createCategory(
            TENANT_A,
            CreateProductCategory(name = "No Optional Values")
        ).code)
    }

    @Test
    fun updatesOnlyTenantCategoryAndPreservesItsActiveState() = runBlocking {
        assertTrue(
            repository.updateCategory(
                TENANT_A,
                CATEGORY_INACTIVE,
                UpdateProductCategory("Renamed Inactive", "RENAMED", CATEGORY_A)
            )
        )
        val updated = repository.getCategories(TENANT_A, includeInactive = true)
            .single { it.id == CATEGORY_INACTIVE }
        assertEquals("Renamed Inactive", updated.name)
        assertEquals("RENAMED", updated.code)
        assertEquals(CATEGORY_A, updated.parentId)
        assertFalse(updated.isActive)

        assertFalse(
            repository.updateCategory(
                TENANT_A,
                CATEGORY_B,
                UpdateProductCategory("Unauthorized", null, null)
            )
        )
        assertEquals(
            "B Category",
            repository.getCategories(TENANT_B).single { it.id == CATEGORY_B }.name
        )
    }

    @Test
    fun rejectsForeignParentSelfParentAndHierarchyCyclesWithoutChangingRows() = runBlocking {
        val categoriesBefore = repository.getCategories(TENANT_A, includeInactive = true)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.createCategory(
                    TENANT_A,
                    CreateProductCategory("Foreign Parent", parentId = CATEGORY_B)
                )
            }
        }
        assertEquals(categoriesBefore, repository.getCategories(TENANT_A, includeInactive = true))

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.updateCategory(
                    TENANT_A,
                    CATEGORY_A,
                    UpdateProductCategory("Self Parent", null, CATEGORY_A)
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.updateCategory(
                    TENANT_A,
                    CATEGORY_A,
                    UpdateProductCategory("Cycle", null, CATEGORY_CHILD)
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.updateCategory(
                    TENANT_A,
                    CATEGORY_A,
                    UpdateProductCategory("Foreign Parent Update", null, CATEGORY_B)
                )
            }
        }
        assertEquals(categoriesBefore, repository.getCategories(TENANT_A, includeInactive = true))

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.createCategory(
                    TENANT_A,
                    CreateProductCategory("Invalid Parent", parentId = "MISSING_PARENT")
                )
            }
        }
        assertEquals(categoriesBefore, repository.getCategories(TENANT_A, includeInactive = true))
    }

    @Test
    fun deactivateIsTenantScopedAndLeavesLinkedProductsAndCategoryReferenceIntact() = runBlocking {
        val productBefore = database.storeDatabaseQueries
            .selectProductByTenantAndId(PRODUCT_A, TENANT_A).executeAsOne()
        val categoryCountBefore = repository.getCategories(TENANT_A, includeInactive = true).size

        assertFalse(repository.deactivateCategory(TENANT_A, CATEGORY_B))
        assertEquals(
            listOf(CATEGORY_B),
            repository.getCategories(TENANT_B).map { it.id }
        )
        assertTrue(repository.deactivateCategory(TENANT_A, CATEGORY_A))

        val productAfter = database.storeDatabaseQueries
            .selectProductByTenantAndId(PRODUCT_A, TENANT_A).executeAsOne()
        assertEquals(productBefore, productAfter)
        assertEquals(CATEGORY_A, productAfter.category_id)
        assertEquals(categoryCountBefore, repository.getCategories(TENANT_A, includeInactive = true).size)
        assertTrue(repository.getCategories(TENANT_A).none { it.id == CATEGORY_A })
        assertFalse(
            repository.getCategories(TENANT_A, includeInactive = true)
                .single { it.id == CATEGORY_A }.isActive
        )
    }

    private fun insertCategory(
        id: String,
        tenantId: String,
        name: String,
        code: String?,
        parentId: String? = null,
        isActive: Boolean = true
    ) {
        val codeSql = code?.let { "'$it'" } ?: "NULL"
        val parentSql = parentId?.let { "'$it'" } ?: "NULL"
        driver.execute(
            null,
            "INSERT INTO categories (id, tenant_id, parent_id, name, code, is_active) VALUES ('$id', '$tenantId', $parentSql, '$name', $codeSql, ${if (isActive) 1 else 0});",
            0
        )
    }

    private companion object {
        const val TENANT_A = "TENANT_CATEGORY_A"
        const val TENANT_B = "TENANT_CATEGORY_B"
        const val CATEGORY_A = "CATEGORY_A"
        const val CATEGORY_CHILD = "CATEGORY_CHILD"
        const val CATEGORY_B = "CATEGORY_B"
        const val CATEGORY_INACTIVE = "CATEGORY_INACTIVE"
        const val PRODUCT_A = "PRODUCT_CATEGORY_A"
    }
}
