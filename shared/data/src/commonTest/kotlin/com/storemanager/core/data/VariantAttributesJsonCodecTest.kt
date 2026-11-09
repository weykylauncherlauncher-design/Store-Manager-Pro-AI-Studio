package com.storemanager.core.data

import com.storemanager.core.domain.model.VariantAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VariantAttributesJsonCodecTest {
    @Test
    fun encodesAndDecodesMap() {
        assertRoundTrip(mapOf("color" to "red", "size" to "M"))
    }

    @Test
    fun encodesAndDecodesEmptyMap() {
        assertRoundTrip(emptyMap())
        assertEquals("{}", VariantAttributesJsonCodec.encode(VariantAttributes(emptyMap())))
    }

    @Test
    fun preservesArabicUnicode() {
        assertRoundTrip(mapOf("اللون" to "أحمر", "المقاس" to "كبير"))
    }

    @Test
    fun preservesQuotesAndBackslashes() {
        assertRoundTrip(mapOf("label" to "\"Red\\Blue\""))
    }

    @Test
    fun rejectsInvalidJson() {
        assertThrows(InvalidVariantAttributesJsonException::class.java) {
            VariantAttributesJsonCodec.decode("{invalid-json}")
        }
    }

    @Test
    fun rejectsNullValues() {
        val error = assertThrows(InvalidVariantAttributesJsonException::class.java) {
            VariantAttributesJsonCodec.decode("{\"color\":null}")
        }
        assertEquals(
            "Variant attributes JSON is invalid or contains a null value",
            error.message
        )
    }

    private fun assertRoundTrip(values: Map<String, String>) {
        val encoded = VariantAttributesJsonCodec.encode(VariantAttributes(values))
        assertEquals(values, VariantAttributesJsonCodec.decode(encoded).values)
    }
}
