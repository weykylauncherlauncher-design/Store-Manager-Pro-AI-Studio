package com.storemanager.core.domain.repository

import com.storemanager.core.domain.model.ProductCategory
import com.storemanager.core.domain.model.CreateProductCategory
import com.storemanager.core.domain.model.UpdateProductCategory

interface ProductCategoryRepository {
    suspend fun getCategories(
        tenantId: String,
        includeInactive: Boolean = false
    ): List<ProductCategory>

    suspend fun createCategory(
        tenantId: String,
        category: CreateProductCategory
    ): ProductCategory

    suspend fun updateCategory(
        tenantId: String,
        categoryId: String,
        category: UpdateProductCategory
    ): Boolean

    suspend fun deactivateCategory(tenantId: String, categoryId: String): Boolean
}
