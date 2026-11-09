package com.storemanager.core.domain.model

/** Normalizes an SKU independently of the device locale. */
fun normalizeSku(value: String): String {
    val normalized = value.trim().uppercase()
    require(normalized.isNotEmpty()) { "SKU must not be blank" }
    return normalized
}
