package com.storemanager.core.data

import com.storemanager.core.domain.model.ProductCategory
import com.storemanager.core.domain.model.CreateProductCategory
import com.storemanager.core.domain.model.UpdateProductCategory
import com.storemanager.core.domain.repository.ProductCategoryRepository

class ProductCategoryUseCases(
    private val repository: ProductCategoryRepository
) {
    suspend fun getCategories(
        session: AuthenticatedSession,
        includeInactive: Boolean = false
    ): List<ProductCategory> {
        PermissionGuard.requirePermission(session, PermissionCodes.PRODUCT_VIEW)
        return repository.getCategories(session.tenantId, includeInactive)
    }

    suspend fun createCategory(
        session: AuthenticatedSession,
        category: CreateProductCategory
    ): ProductCategory {
        PermissionGuard.requirePermission(session, PermissionCodes.CATEGORY_CREATE)
        return repository.createCategory(session.tenantId, category)
    }

    suspend fun updateCategory(
        session: AuthenticatedSession,
        categoryId: String,
        category: UpdateProductCategory
    ): Boolean {
        PermissionGuard.requirePermission(session, PermissionCodes.CATEGORY_UPDATE)
        return repository.updateCategory(session.tenantId, categoryId, category)
    }

    suspend fun deactivateCategory(
        session: AuthenticatedSession,
        categoryId: String
    ): Boolean {
        PermissionGuard.requirePermission(session, PermissionCodes.CATEGORY_DEACTIVATE)
        return repository.deactivateCategory(session.tenantId, categoryId)
    }
}
