package com.storemanager.core.data

import com.storemanager.core.domain.model.CreateProduct
import com.storemanager.core.domain.model.CreateProductVariant
import com.storemanager.core.domain.model.Product
import com.storemanager.core.domain.model.ProductWithVariants
import com.storemanager.core.domain.model.UpdateProduct
import com.storemanager.core.domain.repository.ProductRepository
import java.sql.SQLException
import java.util.UUID

class ProductRepositoryImpl(
    private val database: StoreDatabase
) : ProductRepository {
    private val queries get() = database.storeDatabaseQueries
    private val variantWriteSupport get() = ProductVariantWriteSupport(queries)

    override suspend fun getProducts(
        tenantId: String,
        includeInactive: Boolean
    ): List<Product> = queries.selectProductsByTenant(
        tenantId = tenantId,
        includeInactive = includeInactive.toLong()
    ).executeAsList().map { it.toDomain() }

    override suspend fun getProduct(tenantId: String, productId: String): Product? =
        queries.selectProductByTenantAndId(
            id = productId,
            tenantId = tenantId
        ).executeAsOneOrNull()?.toDomain()

    override suspend fun searchProducts(
        tenantId: String,
        query: String,
        includeInactive: Boolean
    ): List<Product> = queries.searchProductsByTenant(
        tenantId = tenantId,
        includeInactive = includeInactive.toLong(),
        searchPattern = "${query}%",
        barcode = query
    ).executeAsList().map { it.toDomain() }

    override suspend fun createProduct(tenantId: String, product: CreateProduct): Product =
        database.transactionWithResult {
            val id = "PRODUCT_${UUID.randomUUID()}"
            queries.insertProduct(
                id = id,
                tenantId = tenantId,
                categoryId = product.categoryId,
                baseUnitId = product.baseUnitId,
                name = product.name,
                code = product.code,
                barcode = product.barcode,
                trackInventory = 1
            )
            queries.selectProductByTenantAndId(id, tenantId)
                .executeAsOneOrNull()
                ?.toDomain()
                ?: error("Inserted product could not be read back")
        }

    override suspend fun createProductWithVariants(
        tenantId: String,
        product: CreateProduct,
        variants: List<CreateProductVariant>
    ): ProductWithVariants {
        require(variants.isNotEmpty()) { "At least one product variant is required" }

        return try {
            database.transactionWithResult {
                val productId = "PRODUCT_${UUID.randomUUID()}"
                queries.insertProductWithVariants(
                    id = productId,
                    tenantId = tenantId,
                    categoryId = product.categoryId,
                    baseUnitId = product.baseUnitId,
                    name = product.name,
                    code = product.code,
                    barcode = product.barcode,
                    trackInventory = 1
                )

                val createdVariants = variants.map { variant ->
                    variantWriteSupport.createVariantInTransaction(
                        tenantId = tenantId,
                        productId = productId,
                        variant = variant
                    )
                }
                val createdProduct = queries.selectProductByTenantAndId(productId, tenantId)
                    .executeAsOneOrNull()
                    ?.toDomain()
                    ?: error("Inserted product could not be read back")
                ProductWithVariants(createdProduct, createdVariants)
            }
        } catch (error: SQLException) {
            throw variantWriteSupport.translateConstraint(error)
        }
    }

    override suspend fun updateProduct(
        tenantId: String,
        productId: String,
        product: UpdateProduct
    ): Boolean = database.transactionWithResult {
        val existing = queries.selectProductByTenantAndId(productId, tenantId)
            .executeAsOneOrNull()
            ?: return@transactionWithResult false

        queries.updateProductForTenant(
            categoryId = product.categoryId,
            baseUnitId = existing.base_unit_id,
            name = product.name,
            code = product.code,
            barcode = product.barcode,
            trackInventory = existing.track_inventory,
            id = productId,
            tenantId = tenantId
        )
        true
    }

    override suspend fun deactivateProduct(tenantId: String, productId: String): Boolean =
        updateActiveState(tenantId, productId, active = false)

    override suspend fun reactivateProduct(tenantId: String, productId: String): Boolean =
        updateActiveState(tenantId, productId, active = true)

    private fun updateActiveState(
        tenantId: String,
        productId: String,
        active: Boolean
    ): Boolean = database.transactionWithResult {
        if (queries.selectProductByTenantAndId(productId, tenantId).executeAsOneOrNull() == null) {
            return@transactionWithResult false
        }
        if (active) {
            queries.reactivateProductForTenant(productId, tenantId)
        } else {
            queries.deactivateProductForTenant(productId, tenantId)
        }
        true
    }

    private fun Products.toDomain() = Product(
        id = id,
        tenantId = tenant_id,
        name = name,
        categoryId = category_id,
        baseUnitId = base_unit_id,
        code = code,
        barcode = barcode,
        isActive = is_active == 1L,
        createdAt = created_at
    )

    private fun Boolean.toLong() = if (this) 1L else 0L
}
