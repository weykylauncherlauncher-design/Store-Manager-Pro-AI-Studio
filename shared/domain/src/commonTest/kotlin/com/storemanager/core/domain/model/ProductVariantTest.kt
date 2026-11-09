package com.storemanager.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ProductVariantTest {
    @Test
    fun normalizeSkuTrimsAndUppercasesWithoutLocaleState() {
        assertEquals("ABC-01", normalizeSku("ABC-01"))
        assertEquals("ABC-01", normalizeSku("abc-01"))
        assertEquals("ABC-01", normalizeSku(" ABC-01 "))
    }

    @Test
    fun normalizeSkuRejectsBlankAfterTrimming() {
        assertThrowsIllegalArgument { normalizeSku("") }
        assertThrowsIllegalArgument { normalizeSku("   ") }
    }

    @Test
    fun productVariantRepresentsTheDomainFields() {
        val attributes = VariantAttributes(mapOf("color" to "red", "size" to "M"))
        val variant = ProductVariant(
            id = "VARIANT_1",
            productId = "PRODUCT_1",
            sku = normalizeSku(" abc-01 "),
            barcode = "BAR-01",
            name = "Red / M",
            attributes = attributes,
            isActive = true
        )

        assertEquals("PRODUCT_1", variant.productId)
        assertEquals("ABC-01", variant.sku)
        assertEquals(attributes, variant.attributes)
        assertEquals(true, variant.isActive)
    }

    @Test
    fun attributesAreRepresentedAsMapAndNotJson() {
        val attributes = VariantAttributes(mapOf("color" to "red"))
        val variant = CreateProductVariant(
            sku = normalizeSku("abc-01"),
            name = "Red",
            attributes = attributes
        )

        assertEquals(mapOf("color" to "red"), variant.attributes.values)
    }

    @Test
    fun blankAttributeNamesAreRejected() {
        assertThrowsIllegalArgument {
            VariantAttributes(mapOf("" to "red"))
        }
    }

    private fun assertThrowsIllegalArgument(operation: () -> Unit) {
        try {
            operation()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected IllegalArgumentException")
    }
}
