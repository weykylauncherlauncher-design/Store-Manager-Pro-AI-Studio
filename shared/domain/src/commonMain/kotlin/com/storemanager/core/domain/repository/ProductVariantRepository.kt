package com.storemanager.core.domain.repository

import com.storemanager.core.domain.model.CreateProductVariant
import com.storemanager.core.domain.model.ProductVariant
import com.storemanager.core.domain.model.UpdateProductVariant

interface ProductVariantRepository {
    suspend fun getVariants(
        tenantId: String,
        productId: String,
        includeInactive: Boolean = false
    ): List<ProductVariant>

    suspend fun getVariant(
        tenantId: String,
        variantId: String
    ): ProductVariant?

    suspend fun createVariant(
        tenantId: String,
        productId: String,
        variant: CreateProductVariant
    ): ProductVariant

    suspend fun updateVariant(
        tenantId: String,
        variantId: String,
        variant: UpdateProductVariant
    ): Boolean

    suspend fun deactivateVariant(
        tenantId: String,
        variantId: String
    ): Boolean

    suspend fun reactivateVariant(
        tenantId: String,
        variantId: String
    ): Boolean
}
