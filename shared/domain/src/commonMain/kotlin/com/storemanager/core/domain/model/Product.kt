package com.storemanager.core.domain.model

data class Product(
    val id: String,
    val tenantId: String,
    val name: String,
    val categoryId: String?,
    val baseUnitId: String,
    val code: String?,
    val barcode: String?,
    val isActive: Boolean,
    val createdAt: String
)

data class ProductWithVariants(
    val product: Product,
    val variants: List<ProductVariant>
)

data class CreateProduct(
    val name: String,
    val baseUnitId: String,
    val categoryId: String? = null,
    val code: String? = null,
    val barcode: String? = null
)

data class UpdateProduct(
    val name: String,
    val categoryId: String?,
    val code: String?,
    val barcode: String?
)
