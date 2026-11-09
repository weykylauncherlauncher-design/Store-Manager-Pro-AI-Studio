package com.storemanager.core.data

import com.storemanager.core.domain.model.CreateProductVariant
import com.storemanager.core.domain.model.ProductVariant
import com.storemanager.core.domain.model.VariantAttributes
import com.storemanager.core.domain.model.normalizeSku
import java.sql.SQLException
import java.util.UUID

open class ProductVariantRepositoryException(
    message: String,
    cause: Throwable? = null
) : IllegalArgumentException(message, cause)

class ProductVariantTenantException(message: String) : ProductVariantRepositoryException(message)

class ProductVariantModeException(message: String) : ProductVariantRepositoryException(message)

class ProductVariantConstraintException(
    message: String,
    cause: Throwable
) : ProductVariantRepositoryException(message, cause)

internal class ProductVariantWriteSupport(
    private val queries: StoreDatabaseQueries
) {
    fun createVariantInTransaction(
        tenantId: String,
        productId: String,
        variant: CreateProductVariant
    ): ProductVariant {
        val sku = normalizeSku(variant.sku)
        val attributesJson = VariantAttributesJsonCodec.encode(variant.attributes)
        val product = queries.selectProductByTenantAndId(productId, tenantId)
            .executeAsOneOrNull()
            ?: throw ProductVariantTenantException(
                "Product does not belong to the requested tenant"
            )
        if (product.has_variants != 1L) {
            throw ProductVariantModeException(
                "Product is not configured for variants"
            )
        }

        val variantId = "VARIANT_${UUID.randomUUID()}"
        queries.insertProductVariant(
            id = variantId,
            productId = productId,
            sku = sku,
            barcode = variant.barcode,
            variantName = variant.name,
            attributesJson = attributesJson,
            tenantId = tenantId
        )
        return queries.selectVariantByTenantAndId(variantId, tenantId)
            .executeAsOneOrNull()
            ?.toProductVariant()
            ?: error("Inserted product variant could not be read back")
    }

    fun translateConstraint(error: SQLException): ProductVariantConstraintException {
        val message = error.message.orEmpty()
        val normalizedMessage = message.lowercase()
        val userMessage = when {
            normalizedMessage.contains("unique") -> "SKU is already used in this tenant"
            normalizedMessage.contains("check") -> "SKU must be normalized before storage"
            normalizedMessage.contains("foreign key") ||
                normalizedMessage.contains("tenant_mismatch") ->
                "Product does not belong to the requested tenant"
            normalizedMessage.contains("product_not_configured_for_variants") ->
                "Product is not configured for variants"
            else -> "Product variant operation violated a database constraint"
        }
        return ProductVariantConstraintException(userMessage, error)
    }

}

internal fun Product_variants.toProductVariant() = ProductVariant(
    id = id,
    productId = product_id,
    sku = sku,
    barcode = barcode,
    name = variant_name,
    attributes = attributes_json?.let(VariantAttributesJsonCodec::decode)
        ?: VariantAttributes(emptyMap()),
    isActive = is_active == 1L
)
