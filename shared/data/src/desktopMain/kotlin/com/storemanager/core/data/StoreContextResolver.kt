package com.storemanager.core.data

sealed interface StoreContextResolution {
    data object NeedsFirstRunSetup : StoreContextResolution

    data class Ready(
        val tenantId: String,
        val tenantName: String,
        val storeId: String,
        val storeName: String
    ) : StoreContextResolution

    data class SelectionRequired(
        val choices: List<Ready>
    ) : StoreContextResolution

    data object InvalidConfiguration : StoreContextResolution
}

class StoreContextResolver(
    private val database: StoreDatabase
) {
    fun resolve(): StoreContextResolution {
        val queries = database.storeDatabaseQueries
        return resolveRecords(
            queries.selectAllTenants().executeAsList(),
            queries.selectAllStores().executeAsList()
        )
    }
}

internal fun resolveRecords(
    tenants: List<Tenants>,
    stores: List<Stores>
): StoreContextResolution {
    if (tenants.isEmpty()) {
        return if (stores.isEmpty()) {
            StoreContextResolution.NeedsFirstRunSetup
        } else {
            StoreContextResolution.InvalidConfiguration
        }
    }

    if (
        tenants.any { it.id.isBlank() || it.name.isBlank() || it.is_active !in 0L..1L } ||
        tenants.map { it.id }.toSet().size != tenants.size
    ) {
        return StoreContextResolution.InvalidConfiguration
    }

    val tenantsById = tenants.associateBy { it.id }
    if (
        stores.any {
            it.id.isBlank() ||
                it.tenant_id !in tenantsById ||
                it.code.isBlank() ||
                it.name.isBlank() ||
                it.is_active !in 0L..1L
        } ||
        stores.map { it.id }.toSet().size != stores.size
    ) {
        return StoreContextResolution.InvalidConfiguration
    }

    val activeTenants = tenants.filter { it.is_active == 1L }
    if (activeTenants.isEmpty()) return StoreContextResolution.InvalidConfiguration

    val choices = mutableListOf<StoreContextResolution.Ready>()
    for (tenant in activeTenants) {
        val activeStores = stores.filter { it.tenant_id == tenant.id && it.is_active == 1L }
        if (activeStores.isEmpty()) return StoreContextResolution.InvalidConfiguration
        activeStores.forEach { store ->
            choices += StoreContextResolution.Ready(
                tenantId = tenant.id,
                tenantName = tenant.name,
                storeId = store.id,
                storeName = store.name
            )
        }
    }

    return when (choices.size) {
        0 -> StoreContextResolution.InvalidConfiguration
        1 -> choices.single()
        else -> StoreContextResolution.SelectionRequired(choices)
    }
}
