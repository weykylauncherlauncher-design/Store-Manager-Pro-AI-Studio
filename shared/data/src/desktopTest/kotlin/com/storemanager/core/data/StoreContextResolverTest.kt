package com.storemanager.core.data

import app.cash.sqldelight.db.SqlDriver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class StoreContextResolverTest {
    private lateinit var tempDbFile: File
    private lateinit var driver: SqlDriver
    private lateinit var database: StoreDatabase

    @Before
    fun setUp() {
        tempDbFile = File.createTempFile("test_store_context_", ".db")
        tempDbFile.delete()
        driver = DatabaseDriverFactory(tempDbFile).createDriver()
        database = StoreDatabase(driver)
    }

    @After
    fun tearDown() {
        try {
            driver.close()
        } catch (_: Throwable) {
        }
        tempDbFile.delete()
        File("${tempDbFile.absolutePath}-wal").delete()
        File("${tempDbFile.absolutePath}-shm").delete()
        File("${tempDbFile.parentFile.absolutePath}/${tempDbFile.name}.init.lock").delete()
    }

    @Test
    fun emptyDatabaseNeedsFirstRunSetup() {
        assertEquals(
            StoreContextResolution.NeedsFirstRunSetup,
            StoreContextResolver(database).resolve()
        )
    }

    @Test
    fun oneActiveTenantAndItsActiveStoreAreReady() {
        addTenant("TENANT_CONTEXT_A", "Context Enterprise")
        addStore("STORE_CONTEXT_A", "TENANT_CONTEXT_A", "Main Store")

        assertEquals(
            StoreContextResolution.Ready(
                "TENANT_CONTEXT_A",
                "Context Enterprise",
                "STORE_CONTEXT_A",
                "Main Store"
            ),
            StoreContextResolver(database).resolve()
        )
    }

    @Test
    fun tenantWithoutStoreIsInvalid() {
        addTenant("TENANT_CONTEXT_A", "Context Enterprise")

        assertEquals(
            StoreContextResolution.InvalidConfiguration,
            StoreContextResolver(database).resolve()
        )
    }

    @Test
    fun inactiveTenantIsNotSelected() {
        addTenant("TENANT_CONTEXT_A", "Inactive Enterprise", active = 0)
        addStore("STORE_CONTEXT_A", "TENANT_CONTEXT_A", "Inactive Tenant Store")

        assertEquals(
            StoreContextResolution.InvalidConfiguration,
            StoreContextResolver(database).resolve()
        )
    }

    @Test
    fun inactiveStoreIsNotSelected() {
        addTenant("TENANT_CONTEXT_A", "Context Enterprise")
        addStore("STORE_CONTEXT_A", "TENANT_CONTEXT_A", "Inactive Store")
        driver.execute(
            null,
            "UPDATE stores SET is_active = 0 WHERE id = 'STORE_CONTEXT_A'",
            0
        )

        assertEquals(
            StoreContextResolution.InvalidConfiguration,
            StoreContextResolver(database).resolve()
        )
    }

    @Test
    fun multipleActiveTenantStorePairsRequireSelection() {
        addTenant("TENANT_CONTEXT_A", "Enterprise A")
        addTenant("TENANT_CONTEXT_B", "Enterprise B")
        addStore("STORE_CONTEXT_A", "TENANT_CONTEXT_A", "Store A")
        addStore("STORE_CONTEXT_B", "TENANT_CONTEXT_B", "Store B")

        val result = StoreContextResolver(database).resolve()
        assertTrue(result is StoreContextResolution.SelectionRequired)
        assertEquals(
            setOf("TENANT_CONTEXT_A" to "STORE_CONTEXT_A", "TENANT_CONTEXT_B" to "STORE_CONTEXT_B"),
            (result as StoreContextResolution.SelectionRequired).choices
                .map { it.tenantId to it.storeId }
                .toSet()
        )
    }

    @Test
    fun multipleActiveStoresForOneTenantRequireSelectionWhenRepresented() {
        addTenant("TENANT_CONTEXT_A", "Context Enterprise")
        addStore("STORE_CONTEXT_A", "TENANT_CONTEXT_A", "Store A")
        val existingStore = database.storeDatabaseQueries
            .selectStoresByTenantId("TENANT_CONTEXT_A")
            .executeAsOne()

        val result = resolveRecords(
            database.storeDatabaseQueries.selectAllTenants().executeAsList(),
            listOf(
                existingStore,
                existingStore.copy(id = "STORE_CONTEXT_B", code = "B", name = "Store B")
            )
        )

        assertTrue(result is StoreContextResolution.SelectionRequired)
        assertEquals(2, (result as StoreContextResolution.SelectionRequired).choices.size)
    }

    @Test
    fun storeFromAnotherTenantCannotSatisfyActiveTenant() {
        addTenant("TENANT_CONTEXT_A", "Enterprise A")
        addTenant("TENANT_CONTEXT_B", "Enterprise B")
        addStore("STORE_CONTEXT_B", "TENANT_CONTEXT_B", "Store B")

        assertEquals(
            StoreContextResolution.InvalidConfiguration,
            StoreContextResolver(database).resolve()
        )
    }

    private fun addTenant(id: String, name: String, active: Long = 1) {
        database.storeDatabaseQueries.insertTenant(id, name, active)
    }

    private fun addStore(id: String, tenantId: String, name: String) {
        database.storeDatabaseQueries.insertStore(id, tenantId, name, id)
    }
}
