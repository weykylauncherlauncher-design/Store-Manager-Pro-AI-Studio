package com.storemanager.core.data

import com.storemanager.core.domain.model.CreateProductCategory
import com.storemanager.core.domain.model.ProductCategory
import com.storemanager.core.domain.model.UpdateProductCategory
import com.storemanager.core.domain.repository.ProductCategoryRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductCategoryUseCasesTest {
    private val repository = RecordingProductCategoryRepository()
    private val useCases = ProductCategoryUseCases(repository)

    @Test
    fun getCategoriesRequiresProductViewAndUsesSessionTenant() = runBlocking {
        val authenticatedSession = session("TENANT_VIEW", setOf(PermissionCodes.PRODUCT_VIEW))
        repository.categoriesResult = listOf(CATEGORY)

        assertEquals(listOf(CATEGORY), useCases.getCategories(authenticatedSession))
        assertEquals(
            listOf(Call("getCategories", "TENANT_VIEW", includeInactive = false)),
            repository.calls
        )

        repository.reset()
        assertThrows(AuthorizationException::class.java) {
            runBlocking { useCases.getCategories(session("TENANT_DENIED", emptySet())) }
        }
        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun createCategoryRequiresOnlyCategoryCreateAndUsesSessionTenant() = runBlocking {
        val authenticatedSession = session("TENANT_CREATE", setOf(PermissionCodes.CATEGORY_CREATE))
        val input = CreateProductCategory("New category", "NEW", "PARENT")
        val expected = CATEGORY.copy(
            tenantId = authenticatedSession.tenantId,
            name = input.name,
            code = input.code,
            parentId = input.parentId
        )
        repository.createResult = expected

        assertEquals(expected, useCases.createCategory(authenticatedSession, input))
        assertEquals(
            listOf(Call("createCategory", "TENANT_CREATE", createInput = input)),
            repository.calls
        )

        repository.reset()
        assertThrows(AuthorizationException::class.java) {
            runBlocking {
                useCases.createCategory(
                    session("TENANT_DENIED", setOf(PermissionCodes.CATEGORY_UPDATE)),
                    input
                )
            }
        }
        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun updateCategoryRequiresOnlyCategoryUpdateAndUsesSessionTenant() = runBlocking {
        val authenticatedSession = session("TENANT_UPDATE", setOf(PermissionCodes.CATEGORY_UPDATE))
        val input = UpdateProductCategory("Updated category", "UPDATED", null)
        repository.updateResult = true

        assertTrue(useCases.updateCategory(authenticatedSession, CATEGORY.id, input))
        assertEquals(
            listOf(
                Call(
                    "updateCategory",
                    "TENANT_UPDATE",
                    categoryId = CATEGORY.id,
                    updateInput = input
                )
            ),
            repository.calls
        )

        repository.reset()
        assertThrows(AuthorizationException::class.java) {
            runBlocking {
                useCases.updateCategory(
                    session("TENANT_DENIED", setOf(PermissionCodes.CATEGORY_CREATE)),
                    CATEGORY.id,
                    input
                )
            }
        }
        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun deactivateCategoryRequiresOnlyCategoryDeactivateAndUsesSessionTenant() = runBlocking {
        val authenticatedSession = session("TENANT_DEACTIVATE", setOf(PermissionCodes.CATEGORY_DEACTIVATE))
        repository.deactivateResult = true

        assertTrue(useCases.deactivateCategory(authenticatedSession, CATEGORY.id))
        assertEquals(
            listOf(Call("deactivateCategory", "TENANT_DEACTIVATE", categoryId = CATEGORY.id)),
            repository.calls
        )

        repository.reset()
        assertThrows(AuthorizationException::class.java) {
            runBlocking {
                useCases.deactivateCategory(
                    session("TENANT_DENIED", setOf(PermissionCodes.CATEGORY_UPDATE)),
                    CATEGORY.id
                )
            }
        }
        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun categoryPermissionsDoNotSubstituteForEachOtherOrProductView() = runBlocking {
        val createInput = CreateProductCategory("New")
        val updateInput = UpdateProductCategory("Updated", null, null)
        val deniedCalls: List<suspend () -> Unit> = listOf(
            { useCases.getCategories(session("T", setOf(PermissionCodes.CATEGORY_CREATE))) },
            { useCases.createCategory(session("T", setOf(PermissionCodes.CATEGORY_UPDATE)), createInput) },
            { useCases.updateCategory(session("T", setOf(PermissionCodes.CATEGORY_DEACTIVATE)), CATEGORY.id, updateInput) },
            { useCases.deactivateCategory(session("T", setOf(PermissionCodes.CATEGORY_CREATE)), CATEGORY.id) }
        )

        deniedCalls.forEach { operation ->
            assertThrows(AuthorizationException::class.java) {
                runBlocking { operation() }
            }
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
        val includeInactive: Boolean? = null,
        val categoryId: String? = null,
        val createInput: CreateProductCategory? = null,
        val updateInput: UpdateProductCategory? = null
    )

    private class RecordingProductCategoryRepository : ProductCategoryRepository {
        val calls = mutableListOf<Call>()
        var categoriesResult: List<ProductCategory> = emptyList()
        var createResult: ProductCategory = CATEGORY
        var updateResult = false
        var deactivateResult = false

        override suspend fun getCategories(
            tenantId: String,
            includeInactive: Boolean
        ): List<ProductCategory> {
            calls += Call("getCategories", tenantId, includeInactive)
            return categoriesResult
        }

        override suspend fun createCategory(
            tenantId: String,
            category: CreateProductCategory
        ): ProductCategory {
            calls += Call("createCategory", tenantId, createInput = category)
            return createResult
        }

        override suspend fun updateCategory(
            tenantId: String,
            categoryId: String,
            category: UpdateProductCategory
        ): Boolean {
            calls += Call("updateCategory", tenantId, categoryId = categoryId, updateInput = category)
            return updateResult
        }

        override suspend fun deactivateCategory(tenantId: String, categoryId: String): Boolean {
            calls += Call("deactivateCategory", tenantId, categoryId = categoryId)
            return deactivateResult
        }

        fun reset() {
            calls.clear()
            categoriesResult = emptyList()
            createResult = CATEGORY
            updateResult = false
            deactivateResult = false
        }
    }

    private companion object {
        val CATEGORY = ProductCategory(
            id = "CATEGORY_TEST",
            tenantId = "TENANT_TEST",
            parentId = null,
            name = "Test Category",
            code = "TEST",
            isActive = true
        )
    }
}
