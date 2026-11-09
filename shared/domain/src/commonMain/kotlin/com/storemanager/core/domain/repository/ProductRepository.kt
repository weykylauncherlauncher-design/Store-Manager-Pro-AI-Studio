package com.storemanager.core.domain.repository

import com.storemanager.core.domain.model.CreateProduct
import com.storemanager.core.domain.model.CreateProductVariant
import com.storemanager.core.domain.model.Product
import com.storemanager.core.domain.model.ProductWithVariants
import com.storemanager.core.domain.model.UpdateProduct

interface ProductRepository {
    suspend fun getProducts(
        tenantId: String,
        includeInactive: Boolean = false
    ): List<Product>

    suspend fun getProduct(tenantId: String, productId: String): Product?

    suspend fun searchProducts(
        tenantId: String,
        query: String,
        includeInactive: Boolean = false
    ): List<Product>

    suspend fun createProduct(tenantId: String, product: CreateProduct): Product

    suspend fun createProductWithVariants(
        tenantId: String,
        product: CreateProduct,
        variants: List<CreateProductVariant>
    ): ProductWithVariants

    suspend fun updateProduct(
        tenantId: String,
        productId: String,
        product: UpdateProduct
    ): Boolean

    suspend fun deactivateProduct(tenantId: String, productId: String): Boolean

    suspend fun reactivateProduct(tenantId: String, productId: String): Boolean
}
