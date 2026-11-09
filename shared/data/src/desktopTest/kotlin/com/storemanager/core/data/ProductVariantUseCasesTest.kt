package com.storemanager.core.data

import com.storemanager.core.domain.model.CreateProductVariant
import com.storemanager.core.domain.model.ProductVariant
import com.storemanager.core.domain.model.UpdateProductVariant
import com.storemanager.core.domain.model.VariantAttributes
import com.storemanager.core.domain.repository.ProductVariantRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductVariantUseCasesTest {
    private val repository = RecordingProductVariantRepository()
    private val useCases = ProductVariantUseCases(repository)

    @Test
    fun viewOperationsRequireProductViewAndUseSessionTenant() = runBlocking {
        val authenticatedSession = session("TENANT_VIEW", setOf(PermissionCodes.PRODUCT_VIEW))
        repository.variantsResult = listOf(VARIANT)
        repository.variantResult = VARIANT

        assertEquals(
            listOf(VARIANT),
            useCases.getVariants(authenticatedSession, PRODUCT_ID, includeInactive = true)
        )
        assertEquals(VARIANT, useCases.getVariant(authenticatedSession, VARIANT_ID))
        assertEquals(
            listOf(
                Call("getVariants", "TENANT_VIEW", PRODUCT_ID, includeInactive = true),
                Call("getVariant", "TENANT_VIEW", variantId = VARIANT_ID)
            ),
            repository.calls
        )
    }

    @Test
    fun viewOperationsAreDeniedWithoutCallingRepository() = runBlocking {
        val denied = session("TENANT_DENIED", emptySet())

        assertThrows(AuthorizationException::class.java) {
            runBlocking { useCases.getVariants(denied, PRODUCT_ID) }
        }
        assertThrows(AuthorizationException::class.java) {
            runBlocking { useCases.getVariant(denied, VARIANT_ID) }
        }

        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun createUsesProductCreateAndPassesSessionTenant() = runBlocking {
        val authenticatedSession = session("TENANT_CREATE", setOf(PermissionCodes.PRODUCT_CREATE))
        val input = CreateProductVariant(
            sku = "SKU-01",
            name = "Red",
            attributes = VariantAttributes(mapOf("color" to "red"))
        )
        repository.createResult = VARIANT

        assertEquals(VARIANT, useCases.createVariant(authenticatedSession, PRODUCT_ID, input))
        assertEquals(
            listOf(Call("createVariant", "TENANT_CREATE", PRODUCT_ID, createInput = input)),
            repository.calls
        )
    }

    @Test
    fun createIsDeniedWithoutProductCreateAndDoesNotCallRepository() = runBlocking {
        assertThrows(AuthorizationException::class.java) {
            runBlocking {
                useCases.createVariant(
                    session("TENANT_DENIED", setOf(PermissionCodes.PRODUCT_VIEW)),
                    PRODUCT_ID,
                    CreateProductVariant("SKU-01", name = "Red")
                )
            }
        }
        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun updateUsesProductUpdateAndPassesSessionTenant() = runBlocking {
        val authenticatedSession = session("TENANT_UPDATE", setOf(PermissionCodes.PRODUCT_UPDATE))
        val input = UpdateProductVariant("SKU-02", null, "Blue", VariantAttributes(emptyMap()))
        repository.updateResult = true

        assertTrue(useCases.updateVariant(authenticatedSession, VARIANT_ID, input))
        assertEquals(
            listOf(Call("updateVariant", "TENANT_UPDATE", variantId = VARIANT_ID, updateInput = input)),
            repository.calls
        )
    }

    @Test
    fun updateIsDeniedWithoutProductUpdateAndDoesNotCallRepository() = runBlocking {
        assertThrows(AuthorizationException::class.java) {
            runBlocking {
                useCases.updateVariant(
                    session("TENANT_DENIED", setOf(PermissionCodes.PRODUCT_CREATE)),
                    VARIANT_ID,
                    UpdateProductVariant("SKU-02", null, "Blue", VariantAttributes(emptyMap()))
                )
            }
        }
        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun deactivateAndReactivateUseProductDeactivate() = runBlocking {
        val authenticatedSession = session("TENANT_STATE", setOf(PermissionCodes.PRODUCT_DEACTIVATE))
        repository.stateResult = true

        assertTrue(useCases.deactivateVariant(authenticatedSession, VARIANT_ID))
        assertTrue(useCases.reactivateVariant(authenticatedSession, VARIANT_ID))
        assertEquals(
            listOf(
                Call("deactivateVariant", "TENANT_STATE", variantId = VARIANT_ID),
                Call("reactivateVariant", "TENANT_STATE", variantId = VARIANT_ID)
            ),
            repository.calls
        )
    }

    @Test
    fun deactivateAndReactivateAreDeniedWithoutProductDeactivate() = runBlocking {
        val denied = session("TENANT_DENIED", setOf(PermissionCodes.PRODUCT_UPDATE))

        assertThrows(AuthorizationException::class.java) {
            runBlocking { useCases.deactivateVariant(denied, VARIANT_ID) }
        }
        assertThrows(AuthorizationException::class.java) {
            runBlocking { useCases.reactivateVariant(denied, VARIANT_ID) }
        }
        assertTrue(repository.calls.isEmpty())
    }

    private fun session(tenantId: String, permissionCodes: Set<String>) = AuthenticatedSession(
        userId = "USER_TEST",
        tenantId = tenantId,
        storeId = "STORE_TEST",
        username = "test-user",
        fullName = "Test User",
        roleIds = emptySet(),
        permissionCodes = permissionCodes
    )

    private data class Call(
        val operation: String,
        val tenantId: String,
        val productId: String? = null,
        val variantId: String? = null,
        val includeInactive: Boolean? = null,
        val createInput: CreateProductVariant? = null,
        val updateInput: UpdateProductVariant? = null
    )

    private class RecordingProductVariantRepository : ProductVariantRepository {
        val calls = mutableListOf<Call>()
        var variantsResult: List<ProductVariant> = emptyList()
        var variantResult: ProductVariant? = null
        var createResult: ProductVariant = VARIANT
        var updateResult = false
        var stateResult = false

        override suspend fun getVariants(
            tenantId: String,
            productId: String,
            includeInactive: Boolean
        ): List<ProductVariant> {
            calls += Call("getVariants", tenantId, productId, includeInactive = includeInactive)
            return variantsResult
        }

        override suspend fun getVariant(tenantId: String, variantId: String): ProductVariant? {
            calls += Call("getVariant", tenantId, variantId = variantId)
            return variantResult
        }

        override suspend fun createVariant(
            tenantId: String,
            productId: String,
            variant: CreateProductVariant
        ): ProductVariant {
            calls += Call("createVariant", tenantId, productId, createInput = variant)
            return createResult
        }

        override suspend fun updateVariant(
            tenantId: String,
            variantId: String,
            variant: UpdateProductVariant
        ): Boolean {
            calls += Call("updateVariant", tenantId, variantId = variantId, updateInput = variant)
            return updateResult
        }

        override suspend fun deactivateVariant(tenantId: String, variantId: String): Boolean {
            calls += Call("deactivateVariant", tenantId, variantId = variantId)
            return stateResult
        }

        override suspend fun reactivateVariant(tenantId: String, variantId: String): Boolean {
            calls += Call("reactivateVariant", tenantId, variantId = variantId)
            return stateResult
        }
    }

    private companion object {
        const val PRODUCT_ID = "PRODUCT_TEST"
        const val VARIANT_ID = "VARIANT_TEST"
        val VARIANT = ProductVariant(
            id = VARIANT_ID,
            productId = PRODUCT_ID,
            sku = "SKU-01",
            barcode = null,
            name = "Test Variant",
            attributes = VariantAttributes(emptyMap()),
            isActive = true
        )
    }
}
