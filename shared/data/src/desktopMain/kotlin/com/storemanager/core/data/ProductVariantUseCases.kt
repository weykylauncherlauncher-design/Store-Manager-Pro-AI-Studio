package com.storemanager.core.data

import com.storemanager.core.domain.model.CreateProductVariant
import com.storemanager.core.domain.model.ProductVariant
import com.storemanager.core.domain.model.UpdateProductVariant
import com.storemanager.core.domain.repository.ProductVariantRepository

class ProductVariantUseCases(
    private val repository: ProductVariantRepository
) {
    suspend fun getVariants(
        session: AuthenticatedSession,
        productId: String,
        includeInactive: Boolean = false
    ): List<ProductVariant> {
        PermissionGuard.requirePermission(session, PermissionCodes.PRODUCT_VIEW)
        return repository.getVariants(session.tenantId, productId, includeInactive)
    }

    suspend fun getVariant(
        session: AuthenticatedSession,
        variantId: String
    ): ProductVariant? {
        PermissionGuard.requirePermission(session, PermissionCodes.PRODUCT_VIEW)
        return repository.getVariant(session.tenantId, variantId)
    }

    suspend fun createVariant(
        session: AuthenticatedSession,
        productId: String,
        variant: CreateProductVariant
    ): ProductVariant {
        PermissionGuard.requirePermission(session, PermissionCodes.PRODUCT_CREATE)
        return repository.createVariant(session.tenantId, productId, variant)
    }

    suspend fun updateVariant(
        session: AuthenticatedSession,
        variantId: String,
        variant: UpdateProductVariant
    ): Boolean {
        PermissionGuard.requirePermission(session, PermissionCodes.PRODUCT_UPDATE)
        return repository.updateVariant(session.tenantId, variantId, variant)
    }

    suspend fun deactivateVariant(
        session: AuthenticatedSession,
        variantId: String
    ): Boolean {
        PermissionGuard.requirePermission(session, PermissionCodes.PRODUCT_DEACTIVATE)
        return repository.deactivateVariant(session.tenantId, variantId)
    }

    suspend fun reactivateVariant(
        session: AuthenticatedSession,
        variantId: String
    ): Boolean {
        PermissionGuard.requirePermission(session, PermissionCodes.PRODUCT_DEACTIVATE)
        return repository.reactivateVariant(session.tenantId, variantId)
    }
}
