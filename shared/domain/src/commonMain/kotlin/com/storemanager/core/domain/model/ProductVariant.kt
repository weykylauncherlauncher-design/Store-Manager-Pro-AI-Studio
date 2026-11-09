package com.storemanager.core.domain.model

/** Domain representation of variant attributes; storage formats are intentionally excluded. */
data class VariantAttributes(
    val values: Map<String, String>
) {
    init {
        require(values.keys.all { it.isNotBlank() }) {
            "Variant attribute names must not be blank"
        }
    }
}

data class ProductVariant(
    val id: String,
    val productId: String,
    val sku: String,
    val barcode: String?,
    val name: String,
    val attributes: VariantAttributes,
    val isActive: Boolean
)

data class CreateProductVariant(
    val sku: String,
    val barcode: String? = null,
    val name: String,
    val attributes: VariantAttributes = VariantAttributes(emptyMap())
)

data class UpdateProductVariant(
    val sku: String,
    val barcode: String?,
    val name: String,
    val attributes: VariantAttributes
)
