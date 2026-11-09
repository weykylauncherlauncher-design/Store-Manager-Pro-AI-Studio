package com.storemanager.core.data

import com.storemanager.core.domain.model.CreateProduct
import com.storemanager.core.domain.model.CreateProductVariant
import com.storemanager.core.domain.model.Product
import com.storemanager.core.domain.model.ProductWithVariants
import com.storemanager.core.domain.model.UpdateProduct
import com.storemanager.core.domain.repository.ProductRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductUseCasesTest {
    private val repository = RecordingProductRepository()
    private val useCases = ProductUseCases(repository)

    @Test
    fun viewOperationsRequireProductViewAndPassSessionTenantToRepository() = runBlocking {
        val session = session("TENANT_SESSION", setOf(PermissionCodes.PRODUCT_VIEW))

        assertEquals(listOf(PRODUCT.copy(tenantId = session.tenantId)), useCases.getProducts(session))
        assertEquals(PRODUCT.copy(tenantId = session.tenantId), useCases.getProduct(session, PRODUCT.id))
        assertEquals(
            listOf(PRODUCT.copy(tenantId = session.tenantId)),
            useCases.searchProducts(session, "Product")
        )

        assertEquals(listOf("getProducts", "getProduct", "searchProducts"), repository.calls)
        assertEquals(listOf("TENANT_SESSION", "TENANT_SESSION", "TENANT_SESSION"), repository.tenantIds)
    }

    @Test
    fun viewOperationsAreDeniedWithoutCallingRepository() = runBlocking {
        val session = session("TENANT_DENIED", emptySet())

        assertThrows(AuthorizationException::class.java) { runBlocking { useCases.getProducts(session) } }
        assertThrows(AuthorizationException::class.java) {
            runBlocking { useCases.getProduct(session, PRODUCT.id) }
        }
        assertThrows(AuthorizationException::class.java) {
            runBlocking { useCases.searchProducts(session, "Product") }
        }

        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun createAndUpdateRequireTheirExplicitPermissions() = runBlocking {
        val createSession = session("TENANT_CREATE", setOf(PermissionCodes.PRODUCT_CREATE))
        val create = CreateProduct(
            name = "New Product",
            baseUnitId = "UOM_PIECE",
            categoryId = "CATEGORY_SESSION",
            code = "NEW",
            barcode = "NEW-BAR"
        )
        val created = useCases.createProduct(createSession, create)

        assertEquals("TENANT_CREATE", created.tenantId)
        assertEquals(create, repository.createdProduct)
        assertEquals("TENANT_CREATE", repository.tenantIds.single())

        repository.reset()
        val updateSession = session("TENANT_UPDATE", setOf(PermissionCodes.PRODUCT_UPDATE))
        val update = UpdateProduct("Changed", null, "CHANGED", "CHANGED-BAR")
        assertTrue(useCases.updateProduct(updateSession, PRODUCT.id, update))

        assertEquals(update, repository.updatedProduct)
        assertEquals(PRODUCT.id, repository.updatedProductId)
        assertEquals(listOf("TENANT_UPDATE"), repository.tenantIds)
    }

    @Test
    fun createProductWithVariantsUsesAggregateOnceAndPassesSessionTenantAndInputs() = runBlocking {
        val authenticatedSession = session("TENANT_AGGREGATE", setOf(PermissionCodes.PRODUCT_CREATE))
        val product = CreateProduct(
            name = "Variable Product",
            baseUnitId = "UOM_PIECE",
            code = "VARIABLE-CODE",
            barcode = "VARIABLE-BAR"
        )
        val variants = listOf(
            CreateProductVariant("RED-01", name = "Red"),
            CreateProductVariant("BLUE-01", name = "Blue")
        )
        val expected = ProductWithVariants(PRODUCT.copy(tenantId = authenticatedSession.tenantId), emptyList())
        repository.aggregateResult = expected

        assertEquals(expected, useCases.createProductWithVariants(authenticatedSession, product, variants))
        assertEquals(listOf("createProductWithVariants"), repository.calls)
        assertEquals(listOf("TENANT_AGGREGATE"), repository.tenantIds)
        assertEquals(product, repository.aggregateProduct)
        assertEquals(variants, repository.aggregateVariants)
    }

    @Test
    fun createProductWithVariantsIsDeniedWithoutProductCreateAndDoesNotCallRepository() = runBlocking {
        assertThrows(AuthorizationException::class.java) {
            runBlocking {
                useCases.createProductWithVariants(
                    session("TENANT_DENIED", setOf(PermissionCodes.PRODUCT_VIEW)),
                    CreateProduct("Variable Product", "UOM_PIECE"),
                    listOf(CreateProductVariant("SKU-01", name = "Variant"))
                )
            }
        }
        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun createProductWithVariantsRejectsEmptyListWithoutCallingRepository() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                useCases.createProductWithVariants(
                    session("TENANT_EMPTY", setOf(PermissionCodes.PRODUCT_CREATE)),
                    CreateProduct("Variable Product", "UOM_PIECE"),
                    emptyList()
                )
            }
        }
        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun createProductWithVariantsPropagatesRepositoryErrors() = runBlocking {
        val expected = IllegalStateException("aggregate failed")
        repository.aggregateFailure = expected

        val actual = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                useCases.createProductWithVariants(
                    session("TENANT_ERROR", setOf(PermissionCodes.PRODUCT_CREATE)),
                    CreateProduct("Variable Product", "UOM_PIECE"),
                    listOf(CreateProductVariant("SKU-01", name = "Variant"))
                )
            }
        }

        assertEquals(expected, actual)
        assertEquals(listOf("createProductWithVariants"), repository.calls)
    }

    @Test
    fun createAndUpdateAreDeniedWithoutCallingRepository() = runBlocking {
        val product = CreateProduct("New Product", "UOM_PIECE")
        val update = UpdateProduct("Changed", null, null, null)

        assertThrows(AuthorizationException::class.java) {
            runBlocking { useCases.createProduct(session("TENANT", emptySet()), product) }
        }
        assertThrows(AuthorizationException::class.java) {
            runBlocking { useCases.updateProduct(session("TENANT", emptySet()), PRODUCT.id, update) }
        }

        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun deactivateAndReactivateRequireProductDeactivate() = runBlocking {
        val session = session("TENANT_STATE", setOf(PermissionCodes.PRODUCT_DEACTIVATE))

        assertTrue(useCases.deactivateProduct(session, PRODUCT.id))
        assertTrue(useCases.reactivateProduct(session, PRODUCT.id))
        assertEquals(listOf("deactivateProduct", "reactivateProduct"), repository.calls)
        assertEquals(listOf("TENANT_STATE", "TENANT_STATE"), repository.tenantIds)
    }

    @Test
    fun deactivateAndReactivateAreDeniedWithoutCallingRepository() = runBlocking {
        val session = session("TENANT", emptySet())

        assertThrows(AuthorizationException::class.java) {
            runBlocking { useCases.deactivateProduct(session, PRODUCT.id) }
        }
        assertThrows(AuthorizationException::class.java) {
            runBlocking { useCases.reactivateProduct(session, PRODUCT.id) }
        }

        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun cashierAndStockManagerReceiveNoImplicitWritePermissions() = runBlocking {
        val writeOperations: List<suspend (AuthenticatedSession) -> Unit> = listOf(
            { useCases.createProduct(it, CreateProduct("New", "UOM_PIECE")) },
            { useCases.updateProduct(it, PRODUCT.id, UpdateProduct("Changed", null, null, null)) },
            { useCases.deactivateProduct(it, PRODUCT.id) },
            { useCases.reactivateProduct(it, PRODUCT.id) }
        )

        listOf("CASHIER", "STOCK_MANAGER").forEach { role ->
            val session = session(
                tenantId = "TENANT_$role",
                permissionCodes = setOf(PermissionCodes.PRODUCT_VIEW),
                roleIds = setOf(role)
            )
            writeOperations.forEach { operation ->
                assertThrows(AuthorizationException::class.java) {
                    runBlocking { operation(session) }
                }
            }
        }

        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun includeInactiveOptionsArePassedThrough() = runBlocking {
        val session = session("TENANT", setOf(PermissionCodes.PRODUCT_VIEW))

        useCases.getProducts(session, includeInactive = true)
        useCases.searchProducts(session, "Product", includeInactive = true)

        assertEquals(listOf(true), repository.includeInactiveValues)
        assertEquals(listOf(true), repository.searchIncludeInactiveValues)
    }

    private fun session(
        tenantId: String,
        permissionCodes: Set<String>,
        roleIds: Set<String> = emptySet()
    ) = AuthenticatedSession(
        userId = "USER_TEST",
        tenantId = tenantId,
        storeId = "STORE_TEST",
        username = "test-user",
        fullName = "Test User",
        roleIds = roleIds,
        permissionCodes = permissionCodes
    )

    private class RecordingProductRepository : ProductRepository {
        val calls = mutableListOf<String>()
        val tenantIds = mutableListOf<String>()
        val includeInactiveValues = mutableListOf<Boolean>()
        val searchIncludeInactiveValues = mutableListOf<Boolean>()
        var createdProduct: CreateProduct? = null
        var aggregateProduct: CreateProduct? = null
        var aggregateVariants: List<CreateProductVariant>? = null
        var aggregateResult: ProductWithVariants = ProductWithVariants(PRODUCT, emptyList())
        var aggregateFailure: Throwable? = null
        var updatedProduct: UpdateProduct? = null
        var updatedProductId: String? = null

        override suspend fun getProducts(tenantId: String, includeInactive: Boolean): List<Product> {
            record("getProducts", tenantId)
            includeInactiveValues += includeInactive
            return listOf(PRODUCT.copy(tenantId = tenantId))
        }

        override suspend fun getProduct(tenantId: String, productId: String): Product? {
            record("getProduct", tenantId)
            return PRODUCT.takeIf { it.id == productId }?.copy(tenantId = tenantId)
        }

        override suspend fun searchProducts(
            tenantId: String,
            query: String,
            includeInactive: Boolean
        ): List<Product> {
            record("searchProducts", tenantId)
            searchIncludeInactiveValues += includeInactive
            return listOf(PRODUCT.copy(tenantId = tenantId))
        }

        override suspend fun createProduct(tenantId: String, product: CreateProduct): Product {
            record("createProduct", tenantId)
            createdProduct = product
            return PRODUCT.copy(tenantId = tenantId)
        }

        override suspend fun createProductWithVariants(
            tenantId: String,
            product: CreateProduct,
            variants: List<CreateProductVariant>
        ): ProductWithVariants {
            record("createProductWithVariants", tenantId)
            aggregateProduct = product
            aggregateVariants = variants
            aggregateFailure?.let { throw it }
            return aggregateResult.copy(product = aggregateResult.product.copy(tenantId = tenantId))
        }

        override suspend fun updateProduct(
            tenantId: String,
            productId: String,
            product: UpdateProduct
        ): Boolean {
            record("updateProduct", tenantId)
            updatedProductId = productId
            updatedProduct = product
            return true
        }

        override suspend fun deactivateProduct(tenantId: String, productId: String): Boolean {
            record("deactivateProduct", tenantId)
            return true
        }

        override suspend fun reactivateProduct(tenantId: String, productId: String): Boolean {
            record("reactivateProduct", tenantId)
            return true
        }

        fun reset() {
            calls.clear()
            tenantIds.clear()
            includeInactiveValues.clear()
            searchIncludeInactiveValues.clear()
            createdProduct = null
            aggregateProduct = null
            aggregateVariants = null
            aggregateFailure = null
            updatedProduct = null
            updatedProductId = null
        }

        private fun record(operation: String, tenantId: String) {
            calls += operation
            tenantIds += tenantId
        }
    }

    private companion object {
        val PRODUCT = Product(
            id = "PRODUCT_TEST",
            tenantId = "TENANT_TEST",
            name = "Test Product",
            categoryId = null,
            baseUnitId = "UOM_PIECE",
            code = "TEST",
            barcode = "TEST-BAR",
            isActive = true,
            createdAt = "2026-11-07T00:00:00Z"
        )
    }
}
