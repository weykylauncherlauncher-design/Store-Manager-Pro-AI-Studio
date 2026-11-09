package com.storemanager.core.domain.model

data class ProductCategory(
    val id: String,
    val tenantId: String,
    val parentId: String?,
    val name: String,
    val code: String?,
    val isActive: Boolean
)

data class CreateProductCategory(
    val name: String,
    val code: String? = null,
    val parentId: String? = null
)

data class UpdateProductCategory(
    val name: String,
    val code: String?,
    val parentId: String?
)
