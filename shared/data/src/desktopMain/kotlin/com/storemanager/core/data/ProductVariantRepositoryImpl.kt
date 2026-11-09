package com.storemanager.core.data

import com.storemanager.core.domain.model.CreateProductVariant
import com.storemanager.core.domain.model.ProductVariant
import com.storemanager.core.domain.model.UpdateProductVariant
import com.storemanager.core.domain.model.normalizeSku
import com.storemanager.core.domain.repository.ProductVariantRepository
import java.sql.SQLException

class ProductVariantRepositoryImpl(
    private val database: StoreDatabase
) : ProductVariantRepository {
    private val queries get() = database.storeDatabaseQueries
    private val variantWriteSupport get() = ProductVariantWriteSupport(queries)

    override suspend fun getVariants(
        tenantId: String,
        productId: String,
        includeInactive: Boolean
    ): List<ProductVariant> = queries.selectVariantsByTenantAndProduct(
        tenantId = tenantId,
        productId = productId,
        includeInactive = includeInactive.toLong()
    ).executeAsList().map { it.toProductVariant() }

    override suspend fun getVariant(
        tenantId: String,
        variantId: String
    ): ProductVariant? = queries.selectVariantByTenantAndId(
        tenantId = tenantId,
        variantId = variantId
    ).executeAsOneOrNull()?.toProductVariant()

    override suspend fun createVariant(
        tenantId: String,
        productId: String,
        variant: CreateProductVariant
    ): ProductVariant {
        return try {
            database.transactionWithResult {
                variantWriteSupport.createVariantInTransaction(tenantId, productId, variant)
            }
        } catch (error: ProductVariantRepositoryException) {
            throw error
        } catch (error: SQLException) {
            throw variantWriteSupport.translateConstraint(error)
        }
    }

    override suspend fun updateVariant(
        tenantId: String,
        variantId: String,
        variant: UpdateProductVariant
    ): Boolean {
        val sku = normalizeSku(variant.sku)
        val attributesJson = VariantAttributesJsonCodec.encode(variant.attributes)

        return try {
            database.transactionWithResult {
                if (queries.selectVariantByTenantAndId(variantId, tenantId)
                        .executeAsOneOrNull() == null
                ) {
                    return@transactionWithResult false
                }
                queries.updateProductVariantForTenant(
                    sku = sku,
                    barcode = variant.barcode,
                    variantName = variant.name,
                    attributesJson = attributesJson,
                    variantId = variantId,
                    tenantId = tenantId
                )
                true
            }
        } catch (error: SQLException) {
            throw variantWriteSupport.translateConstraint(error)
        }
    }

    override suspend fun deactivateVariant(
        tenantId: String,
        variantId: String
    ): Boolean = updateActiveState(tenantId, variantId, active = false)

    override suspend fun reactivateVariant(
        tenantId: String,
        variantId: String
    ): Boolean = updateActiveState(tenantId, variantId, active = true)

    private fun updateActiveState(
        tenantId: String,
        variantId: String,
        active: Boolean
    ): Boolean = database.transactionWithResult {
        if (queries.selectVariantByTenantAndId(variantId, tenantId)
                .executeAsOneOrNull() == null
        ) {
            return@transactionWithResult false
        }
        if (active) {
            queries.reactivateProductVariantForTenant(variantId, tenantId)
        } else {
            queries.deactivateProductVariantForTenant(variantId, tenantId)
        }
        true
    }

    private fun Boolean.toLong() = if (this) 1L else 0L
}
