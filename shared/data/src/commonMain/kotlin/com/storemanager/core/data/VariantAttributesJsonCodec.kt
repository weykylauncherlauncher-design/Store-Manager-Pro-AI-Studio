package com.storemanager.core.data

import com.storemanager.core.domain.model.VariantAttributes
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class InvalidVariantAttributesJsonException(
    message: String,
    cause: Throwable? = null
) : IllegalArgumentException(message, cause)

object VariantAttributesJsonCodec {
    private val attributesSerializer = MapSerializer(String.serializer(), String.serializer())
    private val json = Json {
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    fun encode(attributes: VariantAttributes): String =
        json.encodeToString(attributesSerializer, attributes.values)

    fun decode(value: String): VariantAttributes = try {
        VariantAttributes(json.decodeFromString(attributesSerializer, value))
    } catch (error: SerializationException) {
        throw InvalidVariantAttributesJsonException(
            "Variant attributes JSON is invalid or contains a null value",
            error
        )
    } catch (error: IllegalArgumentException) {
        throw InvalidVariantAttributesJsonException(
            "Variant attributes JSON contains invalid attribute data",
            error
        )
    }
}
