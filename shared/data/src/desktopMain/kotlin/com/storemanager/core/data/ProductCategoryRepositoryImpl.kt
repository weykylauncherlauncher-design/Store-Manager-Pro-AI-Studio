package com.storemanager.core.data

import com.storemanager.core.domain.model.ProductCategory
import com.storemanager.core.domain.model.CreateProductCategory
import com.storemanager.core.domain.model.UpdateProductCategory
import com.storemanager.core.domain.repository.ProductCategoryRepository
import java.util.UUID

class ProductCategoryRepositoryImpl(
    private val database: StoreDatabase
) : ProductCategoryRepository {
    private val queries get() = database.storeDatabaseQueries

    override suspend fun getCategories(
        tenantId: String,
        includeInactive: Boolean
    ): List<ProductCategory> = queries.selectCategoriesByTenant(
        tenantId = tenantId,
        includeInactive = if (includeInactive) 1L else 0L
    ).executeAsList().map { it.toDomain() }

    override suspend fun createCategory(
        tenantId: String,
        category: CreateProductCategory
    ): ProductCategory = database.transactionWithResult {
        validateParent(tenantId, category.parentId, categoryId = null)
        val id = "CATEGORY_${UUID.randomUUID()}"
        queries.insertCategory(
            id = id,
            tenantId = tenantId,
            parentId = category.parentId,
            name = category.name,
            code = category.code
        )
        queries.selectCategoryByTenantAndId(tenantId, id)
            .executeAsOneOrNull()
            ?.toDomain()
            ?: error("Inserted product category could not be read back")
    }

    override suspend fun updateCategory(
        tenantId: String,
        categoryId: String,
        category: UpdateProductCategory
    ): Boolean = database.transactionWithResult {
        if (queries.selectCategoryByTenantAndId(tenantId, categoryId).executeAsOneOrNull() == null) {
            return@transactionWithResult false
        }
        validateParent(tenantId, category.parentId, categoryId)
        queries.updateCategoryForTenant(
            parentId = category.parentId,
            name = category.name,
            code = category.code,
            tenantId = tenantId,
            categoryId = categoryId
        )
        true
    }

    override suspend fun deactivateCategory(tenantId: String, categoryId: String): Boolean =
        database.transactionWithResult {
            if (queries.selectCategoryByTenantAndId(tenantId, categoryId).executeAsOneOrNull() == null) {
                return@transactionWithResult false
            }
            queries.deactivateCategoryForTenant(tenantId, categoryId)
            true
        }

    private fun validateParent(tenantId: String, parentId: String?, categoryId: String?) {
        var currentParentId = parentId
        val visited = mutableSetOf<String>()
        while (currentParentId != null) {
            require(currentParentId != categoryId) {
                "A product category cannot be its own parent or an ancestor of itself"
            }
            check(visited.add(currentParentId)) {
                "Existing product category hierarchy contains a cycle"
            }
            val parent = queries.selectCategoryByTenantAndId(tenantId, currentParentId)
                .executeAsOneOrNull()
                ?: throw IllegalArgumentException("Parent category is not available in this tenant")
            currentParentId = parent.parent_id
        }
    }

    private fun Categories.toDomain() = ProductCategory(
        id = id,
        tenantId = tenant_id,
        parentId = parent_id,
        name = name,
        code = code,
        isActive = is_active == 1L
    )
}
