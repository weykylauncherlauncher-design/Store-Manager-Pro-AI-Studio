package com.storemanager.core.data

import com.storemanager.core.domain.model.CreateProduct
import com.storemanager.core.domain.model.CreateProductVariant
import com.storemanager.core.domain.model.Product
import com.storemanager.core.domain.model.ProductWithVariants
import com.storemanager.core.domain.model.UpdateProduct
import com.storemanager.core.domain.repository.ProductRepository

class ProductUseCases(
    private val repository: ProductRepository
) {
    suspend fun getProducts(
        session: AuthenticatedSession,
        includeInactive: Boolean = false
    ): List<Product> {
        PermissionGuard.requirePermission(session, PermissionCodes.PRODUCT_VIEW)
        return repository.getProducts(session.tenantId, includeInactive)
    }

    suspend fun getProduct(
        session: AuthenticatedSession,
        productId: String
    ): Product? {
        PermissionGuard.requirePermission(session, PermissionCodes.PRODUCT_VIEW)
        return repository.getProduct(session.tenantId, productId)
    }

    suspend fun searchProducts(
        session: AuthenticatedSession,
        query: String,
        includeInactive: Boolean = false
    ): List<Product> {
        PermissionGuard.requirePermission(session, PermissionCodes.PRODUCT_VIEW)
        return repository.searchProducts(session.tenantId, query, includeInactive)
    }

    suspend fun createProduct(
        session: AuthenticatedSession,
        product: CreateProduct
    ): Product {
        PermissionGuard.requirePermission(session, PermissionCodes.PRODUCT_CREATE)
        return repository.createProduct(session.tenantId, product)
    }

    suspend fun createProductWithVariants(
        session: AuthenticatedSession,
        product: CreateProduct,
        variants: List<CreateProductVariant>
    ): ProductWithVariants {
        PermissionGuard.requirePermission(session, PermissionCodes.PRODUCT_CREATE)
        require(variants.isNotEmpty()) { "At least one product variant is required" }
        return repository.createProductWithVariants(session.tenantId, product, variants)
    }

    suspend fun updateProduct(
        session: AuthenticatedSession,
        productId: String,
        product: UpdateProduct
    ): Boolean {
        PermissionGuard.requirePermission(session, PermissionCodes.PRODUCT_UPDATE)
        return repository.updateProduct(session.tenantId, productId, product)
    }

    suspend fun deactivateProduct(
        session: AuthenticatedSession,
        productId: String
    ): Boolean {
        PermissionGuard.requirePermission(session, PermissionCodes.PRODUCT_DEACTIVATE)
        return repository.deactivateProduct(session.tenantId, productId)
    }

    suspend fun reactivateProduct(
        session: AuthenticatedSession,
        productId: String
    ): Boolean {
        PermissionGuard.requirePermission(session, PermissionCodes.PRODUCT_DEACTIVATE)
        return repository.reactivateProduct(session.tenantId, productId)
    }
}
