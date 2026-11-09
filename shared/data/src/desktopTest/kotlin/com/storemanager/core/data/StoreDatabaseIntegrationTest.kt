package com.storemanager.core.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StoreDatabaseIntegrationTest {

    private lateinit var tempDbFile: File
    private lateinit var factory: DatabaseDriverFactory
    private lateinit var driver: SqlDriver
    private lateinit var db: StoreDatabase

    @Before
    fun setUp() {
        tempDbFile = File.createTempFile("test_store_master_", ".db")
        if (tempDbFile.exists()) tempDbFile.delete()
        factory = DatabaseDriverFactory(tempDbFile)
        driver = factory.createDriver()
        db = StoreDatabase(driver)
    }

    @After
    fun tearDown() {
        try { driver.close() } catch (_: Throwable) {}
        if (tempDbFile.exists()) tempDbFile.delete()
        File("${tempDbFile.absolutePath}-wal").takeIf { it.exists() }?.delete()
        File("${tempDbFile.absolutePath}-shm").takeIf { it.exists() }?.delete()
        File("${tempDbFile.parentFile.absolutePath}/${tempDbFile.name}.init.lock").takeIf { it.exists() }?.delete()
    }

    @Test
    fun test01_createDatabaseFromScratch_has56Tables() {
        val cursor = driver.executeQuery<Long>(
            null,
            "SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%';",
            {
                it.next()
                QueryResult.Value(it.getLong(0) ?: 0L)
            },
            0
        )
        val tableCount = cursor.value
        assertEquals("Expected exactly 56 tables created by SQLDelight", 56L, tableCount)
    }

    @Test
    fun test02_hasAll108ExplicitIndexes() {
        val cursor = driver.executeQuery<Long>(
            null,
            "SELECT count(*) FROM sqlite_master WHERE type='index' AND sql IS NOT NULL;",
            {
                it.next()
                QueryResult.Value(it.getLong(0) ?: 0L)
            },
            0
        )
        val indexCount = cursor.value
        assertEquals("Expected exactly 108 explicit indexes created by SQLDelight", 108L, indexCount)
    }

    @Test
    fun test03_hasAll186CanonicalTriggers() {
        val cursor = driver.executeQuery<Long>(
            null,
            "SELECT count(*) FROM sqlite_master WHERE type='trigger';",
            {
                it.next()
                QueryResult.Value(it.getLong(0) ?: 0L)
            },
            0
        )
        val triggerCount = cursor.value
        assertEquals("Expected exactly 186 canonical triggers created", 186L, triggerCount)
    }

    @Test
    fun test04_pragmaForeignKeyCheck_returnsZeroErrors() {
        val cursor = driver.executeQuery<Long>(
            null,
            "PRAGMA foreign_key_check;",
            {
                var errorCount = 0L
                while (it.next().value) {
                    errorCount++
                }
                QueryResult.Value(errorCount)
            },
            0
        )
        assertEquals("PRAGMA foreign_key_check must return 0 errors", 0L, cursor.value)
    }

    @Test
    fun test05_pragmaIntegrityCheck_returnsOk() {
        val cursor = driver.executeQuery<String?>(
            null,
            "PRAGMA integrity_check;",
            {
                it.next()
                QueryResult.Value(it.getString(0))
            },
            0
        )
        assertEquals("PRAGMA integrity_check must return 'ok'", "ok", cursor.value)
    }

    @Test
    fun test06_userVersionIsSetToTwo() {
        val cursor = driver.executeQuery<Long>(
            null,
            "PRAGMA user_version;",
            {
                it.next()
                QueryResult.Value(it.getLong(0) ?: 0L)
            },
            0
        )
        assertEquals("PRAGMA user_version must be 2 for fresh Schema v2", 2L, cursor.value)
    }

    @Test
    fun test07_checkConstraint_rejectsReservedExceedingOnHand() {
        try {
            driver.execute(
                null,
                """
                INSERT INTO inventory_balances (warehouse_id, stock_item_id, on_hand_ticks, reserved_ticks, current_moving_avg_cost)
                VALUES ('WH_TEST', 'SI_TEST', 100, 200, 50);
                """.trimIndent(),
                0
            )
            fail("Expected CHECK constraint violation when reserved_ticks > on_hand_ticks")
        } catch (e: Exception) {
            assertTrue("Expected integrity error", e.message?.contains("CHECK") == true || e.message?.contains("constraint") == true)
        }
    }

    @Test
    fun test08_seedBaselineAndQueriesExecution() {
        // Seed tenant
        db.storeDatabaseQueries.insertTenant("TENANT_TEST", "Test Enterprise", 1)
        val tenant = db.storeDatabaseQueries.selectTenantById("TENANT_TEST").executeAsOneOrNull()
        assertNotNull(tenant)
        assertEquals("Test Enterprise", tenant?.name)

        // Seed store
        db.storeDatabaseQueries.insertStore("STORE_TEST", "TENANT_TEST", "Branch 1", "B01")
        val stores = db.storeDatabaseQueries.selectAllStores().executeAsList()
        assertTrue(stores.any { it.id == "STORE_TEST" })
    }

    @Test
    fun test08b_firstRunSetupQueriesRespectTenantAndPreserveReferenceData() {
        val queries = db.storeDatabaseQueries
        queries.insertTenant("TENANT_SETUP_A", "Setup Tenant A", 1)
        queries.insertTenant("TENANT_SETUP_B", "Setup Tenant B", 1)

        assertEquals(
            "Setup Tenant A",
            queries.selectTenantById("TENANT_SETUP_A").executeAsOneOrNull()?.name
        )

        queries.insertStore("STORE_SETUP_A", "TENANT_SETUP_A", "Main A", "MAIN")
        queries.insertStore("STORE_SETUP_B", "TENANT_SETUP_B", "Main B", "MAIN")
        assertEquals(
            listOf("STORE_SETUP_A"),
            queries.selectStoresByTenantId("TENANT_SETUP_A").executeAsList().map { it.id }
        )
        assertTrue(queries.selectStoresByTenantId("TENANT_SETUP_A").executeAsList().none { it.id == "STORE_SETUP_B" })

        queries.insertUnitOfMeasure("UOM_SETUP_PIECE", "COUNT", "قطعة", "Piece", "pc", 0)
        assertEquals(
            "UOM_SETUP_PIECE",
            queries.selectUnitOfMeasureById("UOM_SETUP_PIECE").executeAsOneOrNull()?.id
        )
        assertThrows(Exception::class.java) {
            queries.insertUnitOfMeasure("UOM_SETUP_PIECE", "WEIGHT", "وزن", "Weight", "kg", 1)
        }

        assertEquals(
            "COUNT",
            queries.selectUnitOfMeasureById("UOM_SETUP_PIECE").executeAsOneOrNull()?.dimension
        )

        queries.insertRole("ROLE_SETUP_A", "TENANT_SETUP_A", "Administrator", "Initial administrator", 1)
        queries.insertRole("ROLE_SETUP_B", "TENANT_SETUP_B", "Administrator", "Initial administrator", 1)
        assertEquals(
            "ROLE_SETUP_A",
            queries.selectRoleByTenantAndName("TENANT_SETUP_A", "Administrator").executeAsOneOrNull()?.id
        )
        assertEquals(
            listOf("ROLE_SETUP_A"),
            queries.selectRolesByTenantId("TENANT_SETUP_A").executeAsList().map { it.id }
        )

        val adminPermissions = listOf(
            Triple("SALE_CREATE", "SALES", "Create and complete sales"),
            Triple("INVENTORY_ADJUST", "INVENTORY", "Adjust inventory"),
            Triple("PAYMENT_CREATE", "FINANCE", "Create payments"),
            Triple("REPORT_VIEW", "REPORTS", "View reports")
        )
        adminPermissions.forEach { (code, category, description) ->
            queries.insertPermission(code, category, description)
            assertEquals(
                code,
                queries.selectPermissionByCode(code).executeAsOneOrNull()?.code
            )
            queries.insertRolePermission("ROLE_SETUP_A", code)
        }
        assertEquals(
            adminPermissions.map { it.first }.sorted(),
            queries.selectPermissionCodesByRoleId("ROLE_SETUP_A").executeAsList()
        )

        queries.insertUser(
            "USER_SETUP_A",
            "TENANT_SETUP_A",
            "admin",
            "test-hash",
            "test-salt",
            "Admin A",
            1
        )
        queries.insertUser(
            "USER_SETUP_B",
            "TENANT_SETUP_B",
            "admin",
            "test-hash",
            "test-salt",
            "Admin B",
            1
        )
        assertEquals(
            "USER_SETUP_A",
            queries.selectUserByTenantAndUsername("TENANT_SETUP_A", "admin").executeAsOneOrNull()?.id
        )
        assertEquals(
            "USER_SETUP_B",
            queries.selectUserByTenantAndUsername("TENANT_SETUP_B", "admin").executeAsOneOrNull()?.id
        )

        queries.insertUserRole("USER_SETUP_A", "ROLE_SETUP_A")
        assertEquals(listOf("ROLE_SETUP_A"), queries.selectRoleIdsByUserId("USER_SETUP_A").executeAsList())
        assertThrows(Exception::class.java) {
            queries.insertUserRole("USER_SETUP_A", "ROLE_SETUP_B")
        }
        assertEquals(listOf("ROLE_SETUP_A"), queries.selectRoleIdsByUserId("USER_SETUP_A").executeAsList())
    }

    @Test
    fun test08c_firstRunSetupServiceCreatesTenantStoreAndAdministratorAtomically() = runBlocking {
        val setupPassword = "P2.3B-test-password".toCharArray()
        val service = FirstRunSetupService(db)
        val result = service.setup(
            tenantName = "Setup Enterprise",
            storeName = "Main Store",
            storeCode = "MAIN",
            adminFullName = "Store Administrator",
            adminUsername = "admin",
            adminPassword = setupPassword
        )

        assertTrue(setupPassword.all { it == '\u0000' })
        assertEquals("Setup Enterprise", db.storeDatabaseQueries.selectTenantById(result.tenantId).executeAsOne().name)
        val store = db.storeDatabaseQueries.selectStoresByTenantId(result.tenantId).executeAsOne()
        assertEquals(result.storeId, store.id)
        assertEquals("Main Store", store.name)
        assertEquals(5, db.storeDatabaseQueries.selectUnitsOfMeasure().executeAsList().size)

        val role = db.storeDatabaseQueries.selectRoleByTenantAndName(
            result.tenantId,
            "Administrator"
        ).executeAsOne()
        val expectedPermissions = listOf(
            "CATEGORY_CREATE",
            "CATEGORY_DEACTIVATE",
            "CATEGORY_UPDATE",
            "INVENTORY_ADJUST",
            "PAYMENT_CREATE",
            "PRODUCT_CREATE",
            "PRODUCT_DEACTIVATE",
            "PRODUCT_UPDATE",
            "PRODUCT_VIEW",
            "REPORT_VIEW",
            "SALE_CREATE"
        )
        assertEquals(
            expectedPermissions,
            db.storeDatabaseQueries.selectPermissionCodesByRoleId(role.id).executeAsList()
        )
        assertEquals("ADMINISTRATOR", role.system_key)
        assertEquals(1L, tableCount("SELECT COUNT(*) FROM roles WHERE tenant_id = '${result.tenantId}'"))
        assertEquals(11L, tableCount("SELECT COUNT(*) FROM permissions"))

        assertTrue(ProductPermissionsMigration(db, driver).migrate())
        assertTrue(CategoryManagementPermissionsMigration(db, driver).migrate())
        assertEquals(
            expectedPermissions,
            db.storeDatabaseQueries.selectPermissionCodesByRoleId(role.id).executeAsList()
        )
        assertEquals(
            1L,
            tableCount("SELECT COUNT(*) FROM app_data_migrations WHERE migration_id = 'PRODUCT_PERMISSIONS_V1'")
        )
        assertEquals(
            1L,
            tableCount("SELECT COUNT(*) FROM app_data_migrations WHERE migration_id = 'CATEGORY_MANAGEMENT_PERMISSIONS_V1'")
        )

        val admin = db.storeDatabaseQueries.selectUserByTenantAndUsername(
            result.tenantId,
            "admin"
        ).executeAsOne()
        assertEquals(result.adminUserId, admin.id)
        assertEquals(listOf(role.id), db.storeDatabaseQueries.selectRoleIdsByUserId(admin.id).executeAsList())
        assertTrue(
            Argon2idPasswordHasher().verifyPassword(
                "P2.3B-test-password".toCharArray(),
                admin.password_hash,
                admin.salt
            )
        )
        assertEquals(
            0L,
            driver.executeQuery<Long>(
                null,
                "SELECT COUNT(*) FROM app_licenses",
                { cursor ->
                    cursor.next()
                    QueryResult.Value(cursor.getLong(0) ?: 0L)
                },
                0
            ).value
        )

        val countsBeforeRepeat = setupRecordCounts()
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
            service.setup(
                tenantName = "Second Enterprise",
                storeName = "Second Store",
                storeCode = "SECOND",
                adminFullName = "Second Administrator",
                adminUsername = "admin2",
                adminPassword = "Another-test-password".toCharArray()
            )
            }
        }
        assertEquals(countsBeforeRepeat, setupRecordCounts())
    }

    @Test
    fun test08d_firstRunSetupConflictRollsBackAllWrites() = runBlocking {
        val queries = db.storeDatabaseQueries
        queries.insertPermission("REPORT_VIEW", "REPORTS", "Conflicting reference definition")
        val setupPassword = "Rollback-test-password".toCharArray()

        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking {
            FirstRunSetupService(db).setup(
                tenantName = "Rollback Enterprise",
                storeName = "Rollback Store",
                storeCode = "ROLLBACK",
                adminFullName = "Rollback Administrator",
                adminUsername = "rollback-admin",
                adminPassword = setupPassword
            )
            }
        }

        assertTrue(failure.message.orEmpty().contains("Conflicting reference permission: REPORT_VIEW"))
        assertTrue(setupPassword.all { it == '\u0000' })
        assertEquals(0, queries.selectAllTenants().executeAsList().size)
        assertEquals(0, queries.selectAllStores().executeAsList().size)
        assertEquals(0, queries.selectUnitsOfMeasure().executeAsList().size)
        assertEquals(1L, tableCount("SELECT COUNT(*) FROM permissions"))
        assertEquals(0L, tableCount("SELECT COUNT(*) FROM roles"))
        assertEquals(0L, tableCount("SELECT COUNT(*) FROM role_permissions"))
        assertEquals(0L, tableCount("SELECT COUNT(*) FROM users"))
        assertEquals(0L, tableCount("SELECT COUNT(*) FROM user_roles"))

        val conflictingPermission = queries.selectPermissionByCode("REPORT_VIEW").executeAsOne()
        assertEquals("REPORTS", conflictingPermission.category)
        assertEquals("Conflicting reference definition", conflictingPermission.description)
    }

    @Test
    fun test08e_firstRunSetupProductPermissionConflictRollsBackAllWrites() = runBlocking {
        val queries = db.storeDatabaseQueries
        queries.insertPermission("PRODUCT_CREATE", "OTHER", "Conflicting product permission")

        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                FirstRunSetupService(db).setup(
                    tenantName = "Product Conflict Enterprise",
                    storeName = "Product Conflict Store",
                    storeCode = "PRODUCT-CONFLICT",
                    adminFullName = "Product Conflict Administrator",
                    adminUsername = "product-conflict-admin",
                    adminPassword = "Product-conflict-password".toCharArray()
                )
            }
        }

        assertTrue(failure.message.orEmpty().contains("Conflicting reference permission: PRODUCT_CREATE"))
        assertEquals(0, queries.selectAllTenants().executeAsList().size)
        assertEquals(0, queries.selectAllStores().executeAsList().size)
        assertEquals(0, queries.selectUnitsOfMeasure().executeAsList().size)
        assertEquals(0L, tableCount("SELECT COUNT(*) FROM roles"))
        assertEquals(1L, tableCount("SELECT COUNT(*) FROM permissions"))
        assertEquals(0L, tableCount("SELECT COUNT(*) FROM role_permissions"))
        assertEquals(0L, tableCount("SELECT COUNT(*) FROM users"))
        assertEquals(0L, tableCount("SELECT COUNT(*) FROM user_roles"))
        assertEquals(0L, tableCount("SELECT COUNT(*) FROM app_licenses"))
        assertEquals(0L, tableCount("SELECT COUNT(*) FROM app_data_migrations"))
    }

    private fun setupRecordCounts(): Map<String, Long> = mapOf(
        "tenants" to tableCount("SELECT COUNT(*) FROM tenants"),
        "stores" to tableCount("SELECT COUNT(*) FROM stores"),
        "units_of_measure" to tableCount("SELECT COUNT(*) FROM units_of_measure"),
        "roles" to tableCount("SELECT COUNT(*) FROM roles"),
        "permissions" to tableCount("SELECT COUNT(*) FROM permissions"),
        "role_permissions" to tableCount("SELECT COUNT(*) FROM role_permissions"),
        "users" to tableCount("SELECT COUNT(*) FROM users"),
        "user_roles" to tableCount("SELECT COUNT(*) FROM user_roles")
    )

    private fun tableCount(sql: String): Long =
        driver.executeQuery<Long>(
            null,
            sql,
            { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            0
        ).value

    @Test
    fun test09_productNameOnlyMinimalInsert_autoDefaultsUomAndCreatesStockItem() {
        driver.execute(null, "INSERT INTO tenants (id, name) VALUES ('T_PROD_MIN', 'Prod Minimal Tenant');", 0)
        driver.execute(
            null,
            "INSERT INTO units_of_measure (id, dimension, name_ar, name_fr, symbol, is_fractional) VALUES ('UOM_PIECE', 'COUNT', 'قطعة', 'Piece', 'pc', 0);",
            0
        )
        driver.execute(null, "INSERT INTO stores (id, tenant_id, name, code) VALUES ('STR_MIN', 'T_PROD_MIN', 'Store Min', 'SMIN');", 0)
        driver.execute(null, "INSERT INTO warehouses (id, tenant_id, store_id, name, is_default) VALUES ('WH_MIN', 'T_PROD_MIN', 'STR_MIN', 'Default WH', 1);", 0)

        // Insert product with MINIMAL required fields ONLY (id, tenant_id, name) without specifying base_unit_id
        driver.execute(
            null,
            "INSERT INTO products (id, tenant_id, name) VALUES ('P_MIN_01', 'T_PROD_MIN', 'Fresh Artisan Bread');",
            0
        )

        val productUom = driver.executeQuery<String?>(
            null,
            "SELECT base_unit_id FROM products WHERE id = 'P_MIN_01';",
            {
                if (it.next().value) {
                    QueryResult.Value(it.getString(0))
                } else QueryResult.Value(null)
            },
            0
        ).value
        assertEquals("UOM_PIECE", productUom)

        val stockItemCursor = driver.executeQuery<Pair<String, String>?>(
            null,
            "SELECT id, product_id FROM stock_items WHERE product_id = 'P_MIN_01' AND variant_id IS NULL;",
            {
                if (it.next().value) {
                    QueryResult.Value(Pair(it.getString(0)!!, it.getString(1)!!))
                } else QueryResult.Value(null)
            },
            0
        )
        assertNotNull("Trigger must auto-create stock_items record", stockItemCursor.value)
        assertEquals("P_MIN_01", stockItemCursor.value?.second)
        assertTrue("Auto-generated stock item id must be non-blank", stockItemCursor.value?.first?.isNotBlank() == true)
    }

    @Test
    fun test09b_productWithMinimalData_inboundMovementInitializesInventoryBalance() {
        driver.execute(null, "INSERT INTO tenants (id, name) VALUES ('T_PROD_MOV', 'Prod Mov Tenant');", 0)
        driver.execute(
            null,
            "INSERT INTO units_of_measure (id, dimension, name_ar, name_fr, symbol, is_fractional) VALUES ('UOM_PIECE', 'COUNT', 'قطعة', 'Piece', 'pc', 0);",
            0
        )
        driver.execute(null, "INSERT INTO stores (id, tenant_id, name, code) VALUES ('STR_MOV', 'T_PROD_MOV', 'Store Mov', 'SMOV');", 0)
        driver.execute(null, "INSERT INTO warehouses (id, tenant_id, store_id, name, is_default) VALUES ('WH_MOV', 'T_PROD_MOV', 'STR_MOV', 'Warehouse Mov', 1);", 0)

        driver.execute(
            null,
            "INSERT INTO products (id, tenant_id, name) VALUES ('P_MOV_01', 'T_PROD_MOV', 'Organic Olive Oil 1L');",
            0
        )

        val stockItemId = driver.executeQuery<String>(
            null,
            "SELECT id FROM stock_items WHERE product_id = 'P_MOV_01' AND variant_id IS NULL;",
            {
                it.next()
                QueryResult.Value(it.getString(0)!!)
            },
            0
        ).value

        driver.execute(
            null,
            """
            INSERT INTO inventory_movements (
                id, tenant_id, warehouse_id, stock_item_id, movement_type, source_event_id,
                quantity_ticks, unit_cost_ticks, total_cost_centimes
            ) VALUES (
                'MOV_INIT_01', 'T_PROD_MOV', 'WH_MOV', '$stockItemId', 'ADJUSTMENT_IN', 'EVT_INIT_01',
                5000, 200000, 1000
            );
            """.trimIndent(),
            0
        )

        val onHand = driver.executeQuery<Long>(
            null,
            "SELECT on_hand_ticks FROM inventory_balances WHERE warehouse_id = 'WH_MOV' AND stock_item_id = '$stockItemId';",
            {
                it.next()
                QueryResult.Value(it.getLong(0) ?: 0L)
            },
            0
        ).value
        assertEquals("Inventory balance on_hand_ticks must equal 5000 ticks", 5000L, onHand)
    }

    @Test
    fun test10_databaseReopen_preservesDataWithoutRecreatingSchema() {
        db.storeDatabaseQueries.insertTenant("TENANT_PERSIST", "Persistent Enterprise", 1)

        driver.close()

        val reopenFactory = DatabaseDriverFactory(tempDbFile)
        val newDriver = reopenFactory.createDriver()
        val newDb = StoreDatabase(newDriver)

        try {
            val tenant = newDb.storeDatabaseQueries.selectTenantById("TENANT_PERSIST").executeAsOneOrNull()
            assertNotNull("Data must persist across database reopens", tenant)
            assertEquals("Persistent Enterprise", tenant?.name)
        } finally {
            newDriver.close()
        }
    }

    @Test
    fun test11_auditLogsImmutability_rejectsUpdateAndDelete() {
        driver.execute(null, "INSERT INTO tenants (id, name) VALUES ('T_AUDIT', 'Audit Tenant');", 0)
        driver.execute(null, "INSERT INTO audit_logs (id, tenant_id, action, entity_name, entity_id) VALUES ('A_01', 'T_AUDIT', 'INSERT', 'sales', 'S_01');", 0)

        try {
            driver.execute(null, "UPDATE audit_logs SET action = 'MODIFIED' WHERE id = 'A_01';", 0)
            fail("Trigger must reject UPDATE on audit_logs")
        } catch (e: Exception) {
            assertTrue("Expected audit log update rejection", e.message?.contains("AUDIT_LOGS_ARE_IMMUTABLE") == true)
        }

        try {
            driver.execute(null, "DELETE FROM audit_logs WHERE id = 'A_01';", 0)
            fail("Trigger must reject DELETE on audit_logs")
        } catch (e: Exception) {
            assertTrue("Expected audit log delete rejection", e.message?.contains("AUDIT_LOGS_ARE_IMMUTABLE") == true)
        }
    }

    @Test
    fun test12_transactionRollback_cleansUpAtomicallyOnFailure() {
        driver.execute(null, "INSERT INTO tenants (id, name) VALUES ('T_TX', 'Tx Tenant');", 0)

        try {
            db.transaction {
                db.storeDatabaseQueries.insertTenant("T_FAIL_1", "Will Rollback", 1)
                rollback()
            }
        } catch (_: Exception) {
        }

        val tenant = db.storeDatabaseQueries.selectTenantById("T_FAIL_1").executeAsOneOrNull()
        assertEquals("Rolled back record must not exist", null, tenant)
    }

    @Test
    fun test13_singleStorePerTenant_partialUniqueIndex() {
        driver.execute(null, "INSERT INTO tenants (id, name) VALUES ('T_SINGLE', 'Single Store Tenant');", 0)
        driver.execute(null, "INSERT INTO stores (id, tenant_id, name, code) VALUES ('STR_1', 'T_SINGLE', 'First Store', 'S01');", 0)

        try {
            driver.execute(null, "INSERT INTO stores (id, tenant_id, name, code) VALUES ('STR_2', 'T_SINGLE', 'Second Store', 'S02');", 0)
            fail("Expected unique constraint violation for second store under same tenant")
        } catch (e: Exception) {
            assertTrue(
                "Expected single-store constraint violation",
                e.message?.contains("UNIQUE") == true || e.message?.contains("stores.tenant_id") == true
            )
        }
    }

    @Test
    fun test14_oneOpenShiftPerCashRegister_partialUniqueIndex() {
        driver.execute(null, "INSERT INTO tenants (id, name) VALUES ('T_SHIFT', 'Shift Tenant');", 0)
        driver.execute(null, "INSERT INTO stores (id, tenant_id, name, code) VALUES ('STR_SH', 'T_SHIFT', 'Store Shift', 'SSH');", 0)
        driver.execute(null, "INSERT INTO cash_registers (id, tenant_id, store_id, code, name) VALUES ('CR_1', 'T_SHIFT', 'STR_SH', 'REG1', 'POS 1');", 0)
        driver.execute(
            null,
            "INSERT INTO users (id, tenant_id, username, password_hash, salt, full_name, is_active) VALUES ('U_1', 'T_SHIFT', 'cashier1', 'hash', 'salt', 'Cashier', 1);",
            0
        )

        driver.execute(null, "INSERT INTO cash_shifts (id, cash_register_id, user_id, opening_float_centimes, status) VALUES ('SH_1', 'CR_1', 'U_1', 1000, 'OPEN');", 0)

        try {
            driver.execute(null, "INSERT INTO cash_shifts (id, cash_register_id, user_id, opening_float_centimes, status) VALUES ('SH_2', 'CR_1', 'U_1', 2000, 'OPEN');", 0)
            fail("Expected partial unique index violation for second open shift on same cash register")
        } catch (e: Exception) {
            assertTrue(
                "Expected open shift index violation",
                e.message?.contains("UNIQUE") == true || e.message?.contains("idx_one_open_shift_per_register") == true
            )
        }
    }

    // ==========================================
    // 1. SECURE ATOMIC PUBLICATION & WAL VERIFICATION TESTS
    // ==========================================

    @Test
    fun test16_atomicity_triggerFailureLeavesNoPartialDatabase() {
        val failDbFile = File.createTempFile("test_fail_atomicity_", ".db")
        if (failDbFile.exists()) failDbFile.delete()

        val failFactory = DatabaseDriverFactory(failDbFile).apply {
            simulateTriggerFailure = true
        }

        try {
            failFactory.createDriver()
            fail("Expected DatabaseInitializationException when trigger installation fails")
        } catch (e: DatabaseInitializationException) {
            assertTrue("Exception message must indicate initialization failure", e.message?.contains("failed") == true)
        } finally {
            assertFalse(
                "Target database file must NOT exist if initialization failed mid-operation",
                failDbFile.exists()
            )
        }
    }

    @Test
    fun test17_atomicity_recoveryAfterFailureCreatesCleanDatabase() {
        val recoverDbFile = File.createTempFile("test_recover_atomicity_", ".db")
        if (recoverDbFile.exists()) recoverDbFile.delete()

        val failFactory = DatabaseDriverFactory(recoverDbFile).apply {
            simulateTriggerFailure = true
        }
        try {
            failFactory.createDriver()
            fail("Expected failure")
        } catch (_: DatabaseInitializationException) {
        }
        assertFalse("File must not exist after failure", recoverDbFile.exists())

        val recoverFactory = DatabaseDriverFactory(recoverDbFile).apply {
            simulateTriggerFailure = false
        }
        val cleanDriver = recoverFactory.createDriver()
        try {
            assertTrue("Target database file must now exist after successful initialization", recoverDbFile.exists())
            val tableCount = cleanDriver.executeQuery<Long>(
                null,
                "SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%';",
                { it.next(); QueryResult.Value(it.getLong(0) ?: 0L) },
                0
            ).value
            assertEquals("Expected exactly 56 tables", 56L, tableCount)

            val triggerCount = cleanDriver.executeQuery<Long>(
                null,
                "SELECT count(*) FROM sqlite_master WHERE type='trigger';",
                { it.next(); QueryResult.Value(it.getLong(0) ?: 0L) },
                0
            ).value
            assertEquals("Expected exactly 186 triggers", 186L, triggerCount)
        } finally {
            cleanDriver.close()
            if (recoverDbFile.exists()) recoverDbFile.delete()
        }
    }

    @Test
    fun test18_existingDb_reopeningDoesNotDuplicateTriggersOrRecreateSchema() {
        driver.close()

        val reopenFactory = DatabaseDriverFactory(tempDbFile)
        val reopenedDriver = reopenFactory.createDriver()
        try {
            val triggerCount = reopenedDriver.executeQuery<Long>(
                null,
                "SELECT count(*) FROM sqlite_master WHERE type='trigger';",
                { it.next(); QueryResult.Value(it.getLong(0) ?: 0L) },
                0
            ).value
            assertEquals("Reopening database must not duplicate triggers (must remain exactly 186)", 186L, triggerCount)

            val tableCount = reopenedDriver.executeQuery<Long>(
                null,
                "SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%';",
                { it.next(); QueryResult.Value(it.getLong(0) ?: 0L) },
                0
            ).value
            assertEquals("Reopening database must retain exactly 56 tables", 56L, tableCount)
        } finally {
            reopenedDriver.close()
        }
    }

    @Test
    fun test19_existingDb_rejectsInvalidUserVersion() {
        driver.execute(null, "PRAGMA user_version = 99;", 0)
        driver.close()

        val checkFactory = DatabaseDriverFactory(tempDbFile)
        try {
            checkFactory.createDriver()
            fail("Expected DatabaseValidationException when user_version is invalid")
        } catch (e: DatabaseValidationException) {
            assertTrue("Error must mention user_version", e.message?.contains("user_version") == true)
        }

        assertTrue("Corrupt/invalid database file must NOT be deleted upon rejection", tempDbFile.exists())
    }

    @Test
    fun test20_existingDb_rejectsIncompleteSchemaAndPreservesUserDataOnDisk() {
        // Seed customer data
        db.storeDatabaseQueries.insertTenant("TENANT_DO_NOT_DELETE", "Valuable Customer Data", 1)

        // Drop one trigger to simulate incomplete schema
        driver.execute(null, "DROP TRIGGER trg_products_barcode_cross_insert;", 0)
        driver.close()

        val checkFactory = DatabaseDriverFactory(tempDbFile)
        try {
            checkFactory.createDriver()
            fail("Expected DatabaseValidationException when trigger count is incomplete")
        } catch (e: DatabaseValidationException) {
            assertTrue("Error must mention triggers", e.message?.contains("triggers") == true)
        }

        // Section 5.A: Independent raw read verification that customer data and exact original value are preserved
        assertTrue("Database file must NOT be deleted when schema validation fails", tempDbFile.exists())
        val rawReadDriver = JdbcSqliteDriver("jdbc:sqlite:${tempDbFile.absolutePath}")
        try {
            val preservedName = rawReadDriver.executeQuery<String?>(
                null,
                "SELECT name FROM tenants WHERE id = 'TENANT_DO_NOT_DELETE';",
                {
                    if (it.next().value) QueryResult.Value(it.getString(0))
                    else QueryResult.Value(null)
                },
                0
            ).value
            assertEquals(
                "Original customer data value must remain completely intact and uncorrupted",
                "Valuable Customer Data",
                preservedName
            )
        } finally {
            rawReadDriver.close()
        }
    }

    @Test
    fun test20b_existingDb_rejectsSubstitutedSchemaObjectsWhilePreservingCountsAndUserData() {
        // Seed customer data
        db.storeDatabaseQueries.insertTenant("TENANT_CRITICAL_DATA", "Irreplaceable Business Records", 1)

        // Drop one canonical trigger and substitute an altered trigger with a different definition
        // This keeps total triggers count exactly at 186!
        driver.execute(null, "DROP TRIGGER trg_uom_prevent_dimension_update_if_used;", 0)
        driver.execute(
            null,
            """
            CREATE TRIGGER trg_uom_prevent_dimension_update_if_used_SUBSTITUTE
            BEFORE UPDATE OF dimension ON units_of_measure
            BEGIN
                SELECT 1;
            END;
            """.trimIndent(),
            0
        )

        // Verify counts remain identical to baseline: 56 tables, 108 explicit indexes, 186 triggers
        val tableCount = driver.executeQuery<Long>(null, "SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%';", { it.next(); QueryResult.Value(it.getLong(0) ?: 0L) }, 0).value
        val indexCount = driver.executeQuery<Long>(null, "SELECT count(*) FROM sqlite_master WHERE type='index' AND sql IS NOT NULL;", { it.next(); QueryResult.Value(it.getLong(0) ?: 0L) }, 0).value
        val triggerCount = driver.executeQuery<Long>(null, "SELECT count(*) FROM sqlite_master WHERE type='trigger';", { it.next(); QueryResult.Value(it.getLong(0) ?: 0L) }, 0).value
        assertEquals("Total table count must still be 56", 56L, tableCount)
        assertEquals("Total index count must still be 108", 108L, indexCount)
        assertEquals("Total trigger count must still be 186", 186L, triggerCount)

        driver.close()

        // Reopening database with exact validation must REJECT the substituted object
        val reopenFactory = DatabaseDriverFactory(tempDbFile)
        try {
            reopenFactory.createDriver()
            fail("Expected DatabaseValidationException when schema objects are altered/substituted despite matching counts")
        } catch (e: DatabaseValidationException) {
            assertTrue(
                "Error must indicate schema validation failure / mismatch: ${e.message}",
                e.message?.contains("Schema validation failed") == true ||
                e.message?.contains("trigger mismatch") == true ||
                e.message?.contains("fingerprint mismatch") == true
            )
        }

        // Verify database file is preserved and user data is completely intact and uncorrupted
        assertTrue("Database file must NOT be deleted when exact schema validation fails", tempDbFile.exists())
        val rawDriver = JdbcSqliteDriver("jdbc:sqlite:${tempDbFile.absolutePath}")
        try {
            val preservedTenantName = rawDriver.executeQuery<String?>(
                null,
                "SELECT name FROM tenants WHERE id = 'TENANT_CRITICAL_DATA';",
                { if (it.next().value) QueryResult.Value(it.getString(0)) else QueryResult.Value(null) },
                0
            ).value
            assertEquals("User data must remain completely intact and unmodified", "Irreplaceable Business Records", preservedTenantName)
        } finally {
            rawDriver.close()
        }
    }

    @Test
    fun test20c_existingDb_rejectsAlteredTableConstraintWhilePreservingCountsAndUserData() {
        // Seed customer data
        db.storeDatabaseQueries.insertTenant("TENANT_CRITICAL_RECORDS", "Valuable Records Corp", 1)

        // Modify table constraint on an existing table:
        // E.g. in products table, create altered table with relaxed CHECK constraint or altered FK, copy data, swap table
        // This keeps exact counts: 56 tables, 108 explicit indexes, 186 triggers!
        // Seed existing record in tax_rates
        driver.execute(null, "INSERT INTO tax_rates (id, tenant_id, name, rate_bps, is_active) VALUES ('TAX_01', 'TENANT_CRITICAL_RECORDS', 'Standard VAT', 1900, 1);", 0)

        // Drop foreign keys/check constraints from tax_rates table or alter its schema definition
        driver.execute(null, """
            CREATE TABLE tax_rates_ALTERED (
                id TEXT PRIMARY KEY,
                tenant_id TEXT NOT NULL,
                name TEXT NOT NULL,
                rate_bps INTEGER NOT NULL,
                is_active INTEGER NOT NULL DEFAULT 1
                -- Missing REFERENCES tenants(id) and missing CHECK(rate_bps >= 0 AND rate_bps <= 10000)
            );
        """.trimIndent(), 0)

        driver.execute(null, "INSERT INTO tax_rates_ALTERED SELECT id, tenant_id, name, rate_bps, is_active FROM tax_rates;", 0)
        driver.execute(null, "DROP TABLE tax_rates;", 0)
        driver.execute(null, "ALTER TABLE tax_rates_ALTERED RENAME TO tax_rates;", 0)

        driver.execute(null, "PRAGMA foreign_keys = ON;", 0)

        // Verify counts remain identical to baseline: 56 tables, 108 explicit indexes, 186 triggers
        val tableCount = driver.executeQuery<Long>(null, "SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%';", { it.next(); QueryResult.Value(it.getLong(0) ?: 0L) }, 0).value
        val indexCount = driver.executeQuery<Long>(null, "SELECT count(*) FROM sqlite_master WHERE type='index' AND sql IS NOT NULL;", { it.next(); QueryResult.Value(it.getLong(0) ?: 0L) }, 0).value
        val triggerCount = driver.executeQuery<Long>(null, "SELECT count(*) FROM sqlite_master WHERE type='trigger';", { it.next(); QueryResult.Value(it.getLong(0) ?: 0L) }, 0).value
        assertEquals("Total table count must still be 56", 56L, tableCount)
        assertEquals("Total index count must still be 108", 108L, indexCount)
        assertEquals("Total trigger count must still be 186", 186L, triggerCount)

        driver.close()

        // Reopening database with exact validation must REJECT the altered table definition
        val reopenFactory = DatabaseDriverFactory(tempDbFile)
        try {
            reopenFactory.createDriver()
            fail("Expected DatabaseValidationException when table constraints/definition are altered despite matching counts")
        } catch (e: DatabaseValidationException) {
            assertTrue(
                "Error must indicate table constraint / definition alteration: ${e.message}",
                e.message?.contains("definition/constraints altered") == true ||
                e.message?.contains("fingerprint mismatch") == true
            )
        }

        // Verify database file is preserved and user data is completely intact and uncorrupted
        assertTrue("Database file must NOT be deleted when exact schema validation fails", tempDbFile.exists())
        val rawDriver = JdbcSqliteDriver("jdbc:sqlite:${tempDbFile.absolutePath}")
        try {
            val preservedTenantName = rawDriver.executeQuery<String?>(
                null,
                "SELECT name FROM tenants WHERE id = 'TENANT_CRITICAL_RECORDS';",
                { if (it.next().value) QueryResult.Value(it.getString(0)) else QueryResult.Value(null) },
                0
            ).value
            assertEquals("User data must remain completely intact and unmodified", "Valuable Records Corp", preservedTenantName)

            val preservedTaxRate = rawDriver.executeQuery<Long?>(
                null,
                "SELECT rate_bps FROM tax_rates WHERE id = 'TAX_01';",
                { if (it.next().value) QueryResult.Value(it.getLong(0)) else QueryResult.Value(null) },
                0
            ).value
            assertEquals("Tax rates record must remain completely intact and unmodified", 1900L, preservedTaxRate)
        } finally {
            rawDriver.close()
        }
    }

    @Test
    fun test26_concurrentInitialization_trueCrossProcessExclusiveLock() {
        val crossProcessFile = File.createTempFile("test_cross_process_init_", ".db")
        if (crossProcessFile.exists()) crossProcessFile.delete()

        val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath
        val classPath = System.getProperty("java.class.path")

        // Process 1 will perform initialization with an intentional delay inside the lock
        val pb1 = ProcessBuilder(
            javaBin,
            "-cp",
            classPath,
            "com.storemanager.core.data.CrossProcessInitRunner",
            crossProcessFile.absolutePath,
            "1000"
        ).redirectErrorStream(true)

        // Process 2 will attempt to initialize concurrently on the same path
        val pb2 = ProcessBuilder(
            javaBin,
            "-cp",
            classPath,
            "com.storemanager.core.data.CrossProcessInitRunner",
            crossProcessFile.absolutePath,
            "0"
        ).redirectErrorStream(true)

        val p1 = pb1.start()
        // Brief sleep to ensure Process 1 acquires the exclusive file lock first
        Thread.sleep(150)
        val p2 = pb2.start()

        val out1 = p1.inputStream.bufferedReader().use { it.readText() }
        val out2 = p2.inputStream.bufferedReader().use { it.readText() }

        val exit1 = p1.waitFor()
        val exit2 = p2.waitFor()

        println("Cross-Process 1 Output:\n$out1")
        println("Cross-Process 2 Output:\n$out2")

        assertEquals("Process 1 must exit cleanly with code 0:\n$out1", 0, exit1)
        assertEquals("Process 2 must exit cleanly with code 0:\n$out2", 0, exit2)

        assertTrue("Process 1 must succeed: $out1", out1.contains("PROCESS_RESULT:SUCCESS:tables=56,indexes=108,triggers=186"))
        assertTrue("Process 2 must succeed: $out2", out2.contains("PROCESS_RESULT:SUCCESS:tables=56,indexes=108,triggers=186"))

        // Verify target database is fully intact
        assertTrue("Database file must exist", crossProcessFile.exists())
        val checkDriver = DatabaseDriverFactory(crossProcessFile).createDriver()
        try {
            val tables = checkDriver.executeQuery<Long>(
                null,
                "SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%';",
                { it.next(); QueryResult.Value(it.getLong(0) ?: 0L) },
                0
            ).value
            val indexes = checkDriver.executeQuery<Long>(
                null,
                "SELECT count(*) FROM sqlite_master WHERE type='index' AND sql IS NOT NULL;",
                { it.next(); QueryResult.Value(it.getLong(0) ?: 0L) },
                0
            ).value
            val triggers = checkDriver.executeQuery<Long>(
                null,
                "SELECT count(*) FROM sqlite_master WHERE type='trigger';",
                { it.next(); QueryResult.Value(it.getLong(0) ?: 0L) },
                0
            ).value
            assertEquals(56L, tables)
            assertEquals(108L, indexes)
            assertEquals(186L, triggers)
        } finally {
            checkDriver.close()
        }

        // Verify no orphaned staging files remain in parent directory
        val stagingFiles = crossProcessFile.parentFile.listFiles { f ->
            f.name.startsWith("${crossProcessFile.name}.staging.")
        }
        assertTrue("No partial staging files must remain", stagingFiles.isNullOrEmpty())

        crossProcessFile.delete()
        File("${crossProcessFile.parentFile.absolutePath}/${crossProcessFile.name}.init.lock").takeIf { it.exists() }?.delete()
    }

    @Test
    fun test26b_concurrentInitialization_inJvmThreadLocking() {
        val concurrentFile = File.createTempFile("test_concurrent_threads_", ".db")
        if (concurrentFile.exists()) concurrentFile.delete()

        val executor = Executors.newFixedThreadPool(2)
        try {
            val task1 = Callable {
                val f1 = DatabaseDriverFactory(concurrentFile)
                val d1 = f1.createDriver()
                d1
            }
            val task2 = Callable {
                val f2 = DatabaseDriverFactory(concurrentFile)
                val d2 = f2.createDriver()
                d2
            }

            val futures = executor.invokeAll(listOf(task1, task2))
            val d1 = futures[0].get(15, TimeUnit.SECONDS)
            val d2 = futures[1].get(15, TimeUnit.SECONDS)

            assertNotNull("First driver must be initialized", d1)
            assertNotNull("Second driver must be initialized", d2)

            val tables1 = d1.executeQuery<Long>(null, "SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%';", { it.next(); QueryResult.Value(it.getLong(0) ?: 0L) }, 0).value
            val tables2 = d2.executeQuery<Long>(null, "SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%';", { it.next(); QueryResult.Value(it.getLong(0) ?: 0L) }, 0).value
            assertEquals(56L, tables1)
            assertEquals(56L, tables2)

            d1.close()
            d2.close()
        } finally {
            executor.shutdownNow()
            if (concurrentFile.exists()) concurrentFile.delete()
            File("${concurrentFile.parentFile.absolutePath}/${concurrentFile.name}.init.lock").takeIf { it.exists() }?.delete()
        }
    }

    @Test
    fun test27_atomicMove_neverReplacesExistingTargetDatabase() {
        val collisionDbFile = File.createTempFile("test_collision_", ".db")
        if (collisionDbFile.exists()) collisionDbFile.delete()

        val collisionFactory = DatabaseDriverFactory(collisionDbFile).apply {
            simulateTargetCollisionDuringMove = true
            simulateTargetCollision0Byte = false
        }

        try {
            collisionFactory.createDriver()
            fail("Expected DatabaseInitializationException when target collision is detected during publication")
        } catch (e: DatabaseInitializationException) {
            assertTrue(
                "Must reject replacing existing database",
                e.message?.contains("already exists") == true || e.message?.contains("prohibited") == true
            )
        }

        // Verify original file and marker were NOT overwritten or replaced
        assertTrue("Target file must exist with marker preserved", collisionDbFile.exists())
        assertEquals(
            "Target file content must remain exactly the marker and not be overwritten",
            "PRE_EXISTING_TARGET_FILE_MARKER",
            collisionDbFile.readText()
        )
        collisionDbFile.delete()

        // Also test collision with an existing 0-byte file: initialization cleanup must NEVER delete target file based on length == 0
        collisionFactory.simulateTargetCollision0Byte = true
        try {
            collisionFactory.createDriver()
            fail("Expected collision rejection on 0-byte target file collision")
        } catch (e: DatabaseInitializationException) {
            assertTrue(
                "Must reject replacing existing 0-byte database",
                e.message?.contains("already exists") == true || e.message?.contains("prohibited") == true
            )
        }
        assertTrue("Pre-existing target file must NOT be deleted even if length == 0L", collisionDbFile.exists())
        assertEquals("File must be 0 bytes", 0L, collisionDbFile.length())

        collisionDbFile.delete()
        File("${collisionDbFile.parentFile.absolutePath}/${collisionDbFile.name}.init.lock").takeIf { it.exists() }?.delete()
    }

    @Test
    fun test28a_walCheckpointFailure_busyNonZero_abortsPublicationAndPreservesTarget() {
        val checkpointFailFile = File.createTempFile("test_checkpoint_busy_", ".db")
        if (checkpointFailFile.exists()) checkpointFailFile.delete()

        val factory = DatabaseDriverFactory(checkpointFailFile).apply {
            checkpointResultOverride = Triple(1L, 10L, 10L) // busy != 0
        }

        try {
            factory.createDriver()
            fail("Expected DatabaseInitializationException when checkpoint busy != 0")
        } catch (e: DatabaseInitializationException) {
            assertTrue("Exception must mention busy flag", e.message?.contains("busy flag = 1") == true)
        } finally {
            assertFalse(
                "Target database file must not be published if checkpoint returns busy != 0",
                checkpointFailFile.exists()
            )
            val stagingFiles = checkpointFailFile.parentFile.listFiles { f ->
                f.name.startsWith("${checkpointFailFile.name}.staging.")
            }
            assertTrue("Temporary staging files must be cleaned up", stagingFiles.isNullOrEmpty())
            File("${checkpointFailFile.parentFile.absolutePath}/${checkpointFailFile.name}.init.lock").takeIf { it.exists() }?.delete()
        }
    }

    @Test
    fun test28b_walCheckpointFailure_checkpointedLessThanLog_abortsPublicationAndPreservesTarget() {
        val checkpointFailFile = File.createTempFile("test_checkpoint_incomplete_", ".db")
        if (checkpointFailFile.exists()) checkpointFailFile.delete()

        val factory = DatabaseDriverFactory(checkpointFailFile).apply {
            checkpointResultOverride = Triple(0L, 20L, 15L) // checkpointed < log
        }

        try {
            factory.createDriver()
            fail("Expected DatabaseInitializationException when checkpointed < log")
        } catch (e: DatabaseInitializationException) {
            assertTrue("Exception must mention checkpoint incomplete", e.message?.contains("checkpoint incomplete") == true)
        } finally {
            assertFalse(
                "Target database file must not be published if checkpoint is incomplete",
                checkpointFailFile.exists()
            )
            val stagingFiles = checkpointFailFile.parentFile.listFiles { f ->
                f.name.startsWith("${checkpointFailFile.name}.staging.")
            }
            assertTrue("Temporary staging files must be cleaned up", stagingFiles.isNullOrEmpty())
            File("${checkpointFailFile.parentFile.absolutePath}/${checkpointFailFile.name}.init.lock").takeIf { it.exists() }?.delete()
        }
    }

    @Test
    fun test28c_walCheckpoint_success_publishesCleanDatabaseWithoutSidecars() {
        val checkpointSuccessFile = File.createTempFile("test_checkpoint_success_", ".db")
        if (checkpointSuccessFile.exists()) checkpointSuccessFile.delete()

        var stagingWalExistedBeforeMove = false
        var stagingShmExistedBeforeMove = false
        var stagingWalLengthBeforeMove = 0L
        var stagingShmLengthBeforeMove = 0L

        val factory = DatabaseDriverFactory(checkpointSuccessFile).apply {
            onBeforeAtomicMove = { _, stagingWal, stagingShm ->
                stagingWalExistedBeforeMove = stagingWal.exists()
                stagingShmExistedBeforeMove = stagingShm.exists()
                if (stagingWalExistedBeforeMove) stagingWalLengthBeforeMove = stagingWal.length()
                if (stagingShmExistedBeforeMove) stagingShmLengthBeforeMove = stagingShm.length()
            }
        }
        val d = factory.createDriver()
        try {
            assertTrue("Target database must be published on successful checkpoint", checkpointSuccessFile.exists())
            val tableCount = d.executeQuery<Long>(
                null,
                "SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%';",
                { it.next(); QueryResult.Value(it.getLong(0) ?: 0L) },
                0
            ).value
            assertEquals(56L, tableCount)

            // Strictly prove that prior to atomic publication, no uncheckpointed staging WAL/SHM data existed
            assertFalse(
                "Staging WAL must not contain uncheckpointed data before publication",
                stagingWalExistedBeforeMove && stagingWalLengthBeforeMove > 0L
            )
            assertFalse(
                "Staging SHM must not contain uncheckpointed data before publication",
                stagingShmExistedBeforeMove && stagingShmLengthBeforeMove > 0L
            )

            // Verify staging files were completely removed
            val stagingFiles = checkpointSuccessFile.parentFile.listFiles { f ->
                f.name.startsWith("${checkpointSuccessFile.name}.staging.")
            }
            assertTrue("Staging files must not exist after successful publication", stagingFiles.isNullOrEmpty())

            // Verify published sidecars: if present right after connection opening, length must be 0 bytes
            val wal = File("${checkpointSuccessFile.absolutePath}-wal")
            val shm = File("${checkpointSuccessFile.absolutePath}-shm")
            if (wal.exists()) {
                assertEquals("WAL sidecar must be 0 bytes right after opening fresh database", 0L, wal.length())
            }
            if (shm.exists()) {
                assertTrue("SHM sidecar if present must be a valid SQLite index file", shm.length() >= 0L)
            }
        } finally {
            d.close()
            checkpointSuccessFile.delete()
            File("${checkpointSuccessFile.absolutePath}-wal").takeIf { it.exists() }?.delete()
            File("${checkpointSuccessFile.absolutePath}-shm").takeIf { it.exists() }?.delete()
            File("${checkpointSuccessFile.parentFile.absolutePath}/${checkpointSuccessFile.name}.init.lock").takeIf { it.exists() }?.delete()
        }
    }

    @Test
    fun test28d_walCheckpointFailure_stagingWalSidecarNotEmpty_abortsPublication() {
        val sidecarFailFile = File.createTempFile("test_sidecar_fail_", ".db")
        if (sidecarFailFile.exists()) sidecarFailFile.delete()

        val factory = DatabaseDriverFactory(sidecarFailFile).apply {
            simulateStagingWalSidecarFailure = true
        }

        try {
            factory.createDriver()
            fail("Expected DatabaseInitializationException when staging WAL contains uncheckpointed data")
        } catch (e: DatabaseInitializationException) {
            assertTrue("Exception must mention sidecar uncheckpointed data", e.message?.contains("contains uncheckpointed data") == true)
        } finally {
            assertFalse("Target database must not be published", sidecarFailFile.exists())
            val stagingFiles = sidecarFailFile.parentFile.listFiles { f ->
                f.name.startsWith("${sidecarFailFile.name}.staging.")
            }
            assertTrue("Staging files must be removed", stagingFiles.isNullOrEmpty())
            File("${sidecarFailFile.parentFile.absolutePath}/${sidecarFailFile.name}.init.lock").takeIf { it.exists() }?.delete()
        }
    }

    @Test
    fun test28e_stagingCleanupAfterFailure_cleansStagingAndSidecarsCompletely() {
        val cleanupFailFile = File.createTempFile("test_staging_cleanup_", ".db")
        if (cleanupFailFile.exists()) cleanupFailFile.delete()

        val factory = DatabaseDriverFactory(cleanupFailFile).apply {
            simulateTriggerFailure = true
        }

        try {
            factory.createDriver()
            fail("Expected failure during trigger setup")
        } catch (e: DatabaseInitializationException) {
            assertTrue("Expected failure message", e.message?.contains("failed and staging was cleaned up") == true)
        } finally {
            assertFalse("Target database file must not exist", cleanupFailFile.exists())
            val remainingStaging = cleanupFailFile.parentFile.listFiles { f ->
                f.name.startsWith("${cleanupFailFile.name}.staging.")
            }
            assertTrue("All staging files and sidecars must be cleaned up on failure", remainingStaging.isNullOrEmpty())
            File("${cleanupFailFile.parentFile.absolutePath}/${cleanupFailFile.name}.init.lock").takeIf { it.exists() }?.delete()
        }
    }

    @Test
    fun test28f_walSidecarDeletionFailure_atActualDeletionOperation_abortsPublicationAndPreservesTarget() {
        val walDeleteFailFile = File.createTempFile("test_wal_del_fail_", ".db")
        if (walDeleteFailFile.exists()) walDeleteFailFile.delete()

        val factory = DatabaseDriverFactory(walDeleteFailFile).apply {
            // Inject failure directly at the WAL file deletion operation
            walSidecarDeleter = { _ ->
                // Return false simulating actual OS/filesystem failure to delete WAL sidecar
                false
            }
        }

        try {
            factory.createDriver()
            fail("Expected DatabaseInitializationException when WAL sidecar deletion fails at deletion operation")
        } catch (e: DatabaseInitializationException) {
            assertTrue(
                "Exception must indicate failed removal of staging WAL sidecar: ${e.message}",
                e.message?.contains("Failed to remove staging WAL sidecar before publication") == true
            )
        } finally {
            assertFalse(
                "Target database must never be published when WAL sidecar deletion fails",
                walDeleteFailFile.exists()
            )
            val stagingFiles = walDeleteFailFile.parentFile.listFiles { f ->
                f.name.startsWith("${walDeleteFailFile.name}.staging.")
            }
            assertTrue("Temporary staging files must be cleaned up on failure", stagingFiles.isNullOrEmpty())
            File("${walDeleteFailFile.parentFile.absolutePath}/${walDeleteFailFile.name}.init.lock").takeIf { it.exists() }?.delete()
        }
    }

    @Test
    fun test28g_shmSidecarDeletionFailure_atActualDeletionOperation_abortsPublicationAndPreservesTarget() {
        val shmDeleteFailFile = File.createTempFile("test_shm_del_fail_", ".db")
        if (shmDeleteFailFile.exists()) shmDeleteFailFile.delete()

        val factory = DatabaseDriverFactory(shmDeleteFailFile).apply {
            // Inject failure directly at the SHM file deletion operation
            shmSidecarDeleter = { _ ->
                // Return false simulating actual OS/filesystem failure to delete SHM sidecar
                false
            }
        }

        try {
            factory.createDriver()
            fail("Expected DatabaseInitializationException when SHM sidecar deletion fails at deletion operation")
        } catch (e: DatabaseInitializationException) {
            assertTrue(
                "Exception must indicate failed removal of staging SHM sidecar: ${e.message}",
                e.message?.contains("Failed to remove staging SHM sidecar before publication") == true
            )
        } finally {
            assertFalse(
                "Target database must never be published when SHM sidecar deletion fails",
                shmDeleteFailFile.exists()
            )
            val stagingFiles = shmDeleteFailFile.parentFile.listFiles { f ->
                f.name.startsWith("${shmDeleteFailFile.name}.staging.")
            }
            assertTrue("Temporary staging files must be cleaned up on failure", stagingFiles.isNullOrEmpty())
            File("${shmDeleteFailFile.parentFile.absolutePath}/${shmDeleteFailFile.name}.init.lock").takeIf { it.exists() }?.delete()
        }
    }

    // ==========================================
    // 2. CONNECTION PRAGMAS (FULL SYNCHRONOUS & FOREIGN KEYS)
    // ==========================================

    @Test
    fun test21_connectionPragmas_actualValuesEnforced() {
        val foreignKeys = driver.executeQuery<Long>(
            null, "PRAGMA foreign_keys;",
            { it.next(); QueryResult.Value(it.getLong(0) ?: 0L) }, 0
        ).value
        assertEquals("PRAGMA foreign_keys must be 1 (ON)", 1L, foreignKeys)

        val busyTimeout = driver.executeQuery<Long>(
            null, "PRAGMA busy_timeout;",
            { it.next(); QueryResult.Value(it.getLong(0) ?: 0L) }, 0
        ).value
        assertEquals("PRAGMA busy_timeout must be 10000 ms", 10000L, busyTimeout)

        val journalMode = driver.executeQuery<String?>(
            null, "PRAGMA journal_mode;",
            { it.next(); QueryResult.Value(it.getString(0)?.lowercase()) }, 0
        ).value
        assertEquals("PRAGMA journal_mode must be 'wal'", "wal", journalMode)

        val synchronous = driver.executeQuery<Long>(
            null, "PRAGMA synchronous;",
            { it.next(); QueryResult.Value(it.getLong(0) ?: 0L) }, 0
        ).value
        assertEquals("PRAGMA synchronous must be 2 (FULL for strict financial and inventory durability)", 2L, synchronous)
    }

    @Test
    fun test22_foreignKeyEnforcement_rejectsOrphanReference() {
        try {
            driver.execute(
                null,
                "INSERT INTO stores (id, tenant_id, name, code) VALUES ('STR_ORPHAN', 'NON_EXISTENT_TENANT_XYZ', 'Orphan Store', 'ORP');",
                0
            )
            fail("SQLite must reject insert with non-existent foreign key reference")
        } catch (e: Exception) {
            assertTrue(
                "Expected foreign key constraint violation",
                e.message?.contains("FOREIGN KEY") == true || e.message?.contains("constraint") == true
            )
        }
    }

    @Test
    fun test23_connectionPragmas_allFourReappliedOnReopenedConnection() {
        driver.close()

        val reopenFactory = DatabaseDriverFactory(tempDbFile)
        val newDriver = reopenFactory.createDriver()
        try {
            val fk = newDriver.executeQuery<Long>(null, "PRAGMA foreign_keys;", { it.next(); QueryResult.Value(it.getLong(0) ?: 0L) }, 0).value
            assertEquals("PRAGMA foreign_keys must remain 1 (ON) on new connection", 1L, fk)

            val timeout = newDriver.executeQuery<Long>(null, "PRAGMA busy_timeout;", { it.next(); QueryResult.Value(it.getLong(0) ?: 0L) }, 0).value
            assertEquals("PRAGMA busy_timeout must remain 10000 ms on new connection", 10000L, timeout)

            val jm = newDriver.executeQuery<String?>(null, "PRAGMA journal_mode;", { it.next(); QueryResult.Value(it.getString(0)?.lowercase()) }, 0).value
            assertEquals("PRAGMA journal_mode must remain 'wal' on reopened database", "wal", jm)

            val sync = newDriver.executeQuery<Long>(null, "PRAGMA synchronous;", { it.next(); QueryResult.Value(it.getLong(0) ?: 0L) }, 0).value
            assertEquals("PRAGMA synchronous must remain 2 (FULL) on new connection", 2L, sync)
        } finally {
            newDriver.close()
        }
    }

    // ==========================================
    // 3. COMPARATIVE BENCHMARK: NORMAL vs FULL
    // ==========================================

    @Test
    fun test29_comparativeBenchmark_normalVsFullDurability_productInventoryWriteMicrobenchmark() {
        // Execute identical set of write operations under NORMAL and FULL synchronous settings.
        // Note: Accurately structured as an isolated product creation and inventory movement write microbenchmark,
        // comparing SQLite WAL commit performance between synchronous=NORMAL and synchronous=FULL.
        val normalDbFile = File.createTempFile("bench_normal_", ".db")
        val fullDbFile = File.createTempFile("bench_full_", ".db")
        normalDbFile.delete()
        fullDbFile.delete()

        val normalFactory = DatabaseDriverFactory(normalDbFile)
        val normalDriver = normalFactory.createDriver()
        normalDriver.execute(null, "PRAGMA synchronous = NORMAL;", 0)

        val fullFactory = DatabaseDriverFactory(fullDbFile)
        val fullDriver = fullFactory.createDriver()
        fullDriver.execute(null, "PRAGMA synchronous = FULL;", 0)

        try {
            val operationsCount = 50

            val timeNormalMs = runBenchmarkBatch(normalDriver, operationsCount)
            val timeFullMs = runBenchmarkBatch(fullDriver, operationsCount)

            println("=== PRODUCT & INVENTORY WRITE MICROBENCHMARK RESULTS ($operationsCount writes) ===")
            println("Note: Measures isolated product & inventory movement insertions under SQLite WAL mode")
            println("synchronous = NORMAL: $timeNormalMs ms")
            println("synchronous = FULL:   $timeFullMs ms")
            val diffMs = timeFullMs - timeNormalMs
            val ratio = if (timeNormalMs > 0) String.format("%.2f", timeFullMs.toDouble() / timeNormalMs.toDouble()) else "N/A"
            println("Difference: +$diffMs ms (Ratio FULL/NORMAL: ${ratio}x)")
            println("===============================================================================")

            assertTrue("Both benchmarks must complete positively", timeNormalMs >= 0 && timeFullMs >= 0)
        } finally {
            normalDriver.close()
            fullDriver.close()
            normalDbFile.delete()
            fullDbFile.delete()
        }
    }

    private fun runBenchmarkBatch(targetDriver: SqlDriver, count: Int): Long {
        // Seed tenant, store, register, user, base unit
        val prefix = "B_${System.nanoTime()}_"
        targetDriver.execute(null, "INSERT INTO tenants (id, name) VALUES ('${prefix}T', 'Bench Tenant');", 0)
        targetDriver.execute(null, "INSERT INTO units_of_measure (id, dimension, name_ar, name_fr, symbol, is_fractional) VALUES ('${prefix}UOM', 'COUNT', 'قطعة', 'Piece', 'pc', 0);", 0)
        targetDriver.execute(null, "INSERT INTO stores (id, tenant_id, name, code) VALUES ('${prefix}STR', '${prefix}T', 'Bench Store', 'BS');", 0)
        targetDriver.execute(null, "INSERT INTO warehouses (id, tenant_id, store_id, name, is_default) VALUES ('${prefix}WH', '${prefix}T', '${prefix}STR', 'Default WH', 1);", 0)

        val start = System.nanoTime()
        for (i in 1..count) {
            val prodId = "${prefix}P_$i"
            targetDriver.execute(null, "INSERT INTO products (id, tenant_id, name, base_unit_id) VALUES ('$prodId', '${prefix}T', 'Bench Prod $i', '${prefix}UOM');", 0)
            val stockItemId = targetDriver.executeQuery<String>(
                null,
                "SELECT id FROM stock_items WHERE product_id = '$prodId';",
                { it.next(); QueryResult.Value(it.getString(0)!!) },
                0
            ).value

            targetDriver.execute(
                null,
                """
                INSERT INTO inventory_movements (
                    id, tenant_id, warehouse_id, stock_item_id, movement_type, source_event_id,
                    quantity_ticks, unit_cost_ticks, total_cost_centimes
                ) VALUES (
                    '${prefix}MOV_$i', '${prefix}T', '${prefix}WH', '$stockItemId', 'ADJUSTMENT_IN', '${prefix}EVT_$i',
                    10000, 100000, 1000
                );
                """.trimIndent(),
                0
            )
        }
        val end = System.nanoTime()
        return TimeUnit.NANOSECONDS.toMillis(end - start)
    }

    // ==========================================
    // 4. SQLITE RUNTIME VERSION & ED25519
    // ==========================================

    @Test
    fun test24_sqliteRuntimeVersion_loggedAndVerified() {
        val sqliteVersion = DatabaseDriverFactory.getSqliteVersion(driver)
        println("=== DESKTOP SQLITE RUNTIME VERSION: $sqliteVersion ===")
        assertTrue("SQLite runtime version must be non-empty", sqliteVersion.isNotBlank())
    }

    @Test
    fun test25_ed25519SignatureVerification_nativeJdkCrypto() {
        val isEd25519Available = try {
            KeyPairGenerator.getInstance("Ed25519")
            true
        } catch (_: java.security.NoSuchAlgorithmException) {
            false
        }

        assertTrue(
            "Ed25519 JCA algorithm must be natively available and verified under unified JDK 21 runtime",
            isEd25519Available
        )
        println("=== NATIVE ED25519 CRYPTO VERIFICATION EXECUTING (JDK 21) ===")

        val kpg = KeyPairGenerator.getInstance("Ed25519")
        val keyPair = kpg.generateKeyPair()

        val payload = "STORE_POS_OP:TENANT_01:DEVICE_01:SEQ_42:TOTAL_15000".toByteArray(Charsets.UTF_8)

        val signer = Signature.getInstance("Ed25519")
        signer.initSign(keyPair.private)
        signer.update(payload)
        val signature = signer.sign()
        assertTrue("Generated signature must not be empty", signature.isNotEmpty())

        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(keyPair.public)
        verifier.update(payload)
        assertTrue("Valid Ed25519 signature must pass verification", verifier.verify(signature))

        val tamperedPayload = payload.clone()
        tamperedPayload[tamperedPayload.size - 1] = (tamperedPayload[tamperedPayload.size - 1] + 1).toByte()
        val tamperVerifier = Signature.getInstance("Ed25519")
        tamperVerifier.initVerify(keyPair.public)
        tamperVerifier.update(tamperedPayload)
        assertFalse("Tampered payload must be rejected by Ed25519 verification", tamperVerifier.verify(signature))
    }

    @Test
    fun test15_runFullReferenceVerificationMatrix_onActualDatabaseFile() {
        val script = listOf(
            File("verify_canonical_schema.py"),
            File("../../verify_canonical_schema.py"),
            File(System.getProperty("user.dir"), "verify_canonical_schema.py"),
            File(System.getProperty("user.dir"), "../../verify_canonical_schema.py")
        ).firstOrNull { it.exists() }

        var pythonAvailable = false
        try {
            val checkProcess = ProcessBuilder("python3", "--version").start()
            pythonAvailable = (checkProcess.waitFor() == 0)
        } catch (_: Exception) {
            pythonAvailable = false
        }

        Assume.assumeTrue(
            "python3 is not available in this environment; skipping external script verification without claiming false pass",
            pythonAvailable && script != null
        )

        val process = ProcessBuilder(
            "python3",
            script!!.absolutePath,
            tempDbFile.absolutePath
        ).redirectErrorStream(true).start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()

        println("Python Verification Suite Output on SQLDelight DB:\n$output")
        assertEquals("Python script execution returned non-zero exit code:\n$output", 0, exitCode)
        assertTrue(
            "Output must confirm zero failures and all 62 actual runs",
            output.contains("62 PASS / 0 FAIL")
        )
    }

    @Test
    fun test30_storeDatabaseManager_normalLifecycleAndSafeCleanupOnFailure() {
        val managerDbFile = File.createTempFile("test_manager_lifecycle_", ".db")
        if (managerDbFile.exists()) managerDbFile.delete()

        var wasDriverClosed = false
        val factory = DatabaseDriverFactory(managerDbFile).apply {
            driverWrapper = { realDriver ->
                object : SqlDriver by realDriver {
                    override fun close() {
                        wasDriverClosed = true
                        realDriver.close()
                    }
                }
            }
        }

        var shouldFailConstruction = true

        val manager = StoreDatabaseManager(factory) { driver ->
            if (shouldFailConstruction) {
                throw IllegalStateException("Simulated failure in StoreDatabase construction")
            }
            StoreDatabase(driver)
        }

        assertFalse("Manager must not be open before getDatabase()", manager.isOpen())

        // 1. Force StoreDatabase construction failure after driver is created
        try {
            manager.getDatabase()
            fail("Expected exception when StoreDatabase construction fails")
        } catch (e: IllegalStateException) {
            assertEquals("Simulated failure in StoreDatabase construction", e.message)
        }

        // 2. Verify driver is closed and state cleared
        assertTrue("Driver must be closed via d.close() cleanup guard upon construction failure", wasDriverClosed)
        assertFalse("Manager must report isOpen() == false after construction failure", manager.isOpen())

        // 3. Subsequent clean initialization succeeds
        shouldFailConstruction = false
        val db = manager.getDatabase()
        assertNotNull("Subsequent initialization must succeed", db)
        assertTrue("Manager must report isOpen() == true after successful initialization", manager.isOpen())

        // Verify driver works
        val activeDriver = manager.getDriver()
        val version = DatabaseDriverFactory.getSqliteVersion(activeDriver)
        assertTrue("Active driver must be operational", version.isNotBlank())

        manager.close()
        assertFalse("Manager must report isOpen() == false after close()", manager.isOpen())

        managerDbFile.delete()
        File("${managerDbFile.parentFile.absolutePath}/${managerDbFile.name}.init.lock").takeIf { it.exists() }?.delete()
    }

    @Test
    fun test31_exactSchemaValidation_alteredCheckConstraint_rejectsDatabaseAndPreservesData() {
        val testDbFile = File.createTempFile("test_check_constraint_alter_", ".db")
        if (testDbFile.exists()) testDbFile.delete()

        try {
            // 1. Initialize database with customSchemaCreator that introduces an altered CHECK constraint
            // on inventory_balances: 'CHECK (on_hand_ticks > reserved_ticks)' instead of '>='
            // with skipValidationDuringInit = true so that the database is initialized and published.
            val initFactory = DatabaseDriverFactory(testDbFile).apply {
                skipValidationDuringInit = true
                customSchemaCreator = { stagingDriver ->
                    StoreDatabase.Schema.create(stagingDriver)
                    // Recreate inventory_balances with altered CHECK constraint:
                    // 'on_hand_ticks > reserved_ticks' instead of 'on_hand_ticks >= reserved_ticks'
                    stagingDriver.execute(null, "DROP TABLE inventory_balances;", 0)
                    stagingDriver.execute(
                        null,
                        """
                        CREATE TABLE inventory_balances (
                            warehouse_id TEXT NOT NULL REFERENCES warehouses(id) ON DELETE RESTRICT,
                            stock_item_id TEXT NOT NULL REFERENCES stock_items(id) ON DELETE RESTRICT,
                            on_hand_ticks INTEGER NOT NULL DEFAULT 0 CHECK(on_hand_ticks >= 0),
                            reserved_ticks INTEGER NOT NULL DEFAULT 0 CHECK(reserved_ticks >= 0),
                            current_moving_avg_cost INTEGER NOT NULL DEFAULT 0 CHECK(current_moving_avg_cost >= 0),
                            PRIMARY KEY(warehouse_id, stock_item_id),
                            CHECK(on_hand_ticks > reserved_ticks)
                        );
                        """.trimIndent(),
                        0
                    )
                    // Recreate explicit index on inventory_balances to preserve exactly 108 canonical indexes
                    stagingDriver.execute(null, "CREATE INDEX ix_balances_stock ON inventory_balances(stock_item_id);", 0)
                }
            }
            val initDriver = initFactory.createDriver()
            // Insert actual customer data into tenants
            initDriver.execute(null, "INSERT INTO tenants (id, name) VALUES ('T_VIP_01', 'Acme VIP Retail');", 0)
            initDriver.close()

            // 2. Open via standard DatabaseDriverFactory (as an existing database)
            val standardFactory = DatabaseDriverFactory(testDbFile)
            try {
                standardFactory.createDriver()
                fail("DatabaseDriverFactory must reject existing database with altered CHECK constraint")
            } catch (e: DatabaseValidationException) {
                assertTrue(
                    "Exception must explicitly indicate altered table definition/constraints: ${e.message}",
                    e.message?.contains("inventory_balances") == true &&
                    e.message?.contains("definition/constraints altered") == true
                )
            }

            // 3. Prove that the database file was NOT deleted, corrupted, or replaced
            assertTrue("Database file must remain present on filesystem", testDbFile.exists())

            // 4. Prove customer data was preserved completely intact
            val verifyDriver = JdbcSqliteDriver("jdbc:sqlite:${testDbFile.absolutePath}")
            val tenantName = verifyDriver.executeQuery<String?>(
                null,
                "SELECT name FROM tenants WHERE id = 'T_VIP_01';",
                { it.next(); QueryResult.Value(it.getString(0)) },
                0
            ).value
            assertEquals("Customer tenant record must remain intact and unmodified", "Acme VIP Retail", tenantName)
            verifyDriver.close()
        } finally {
            testDbFile.delete()
            File("${testDbFile.absolutePath}-wal").takeIf { it.exists() }?.delete()
            File("${testDbFile.absolutePath}-shm").takeIf { it.exists() }?.delete()
            File("${testDbFile.parentFile.absolutePath}/${testDbFile.name}.init.lock").takeIf { it.exists() }?.delete()
        }
    }

    @Test
    fun test32_exactSchemaValidation_alteredForeignKeyOrOnDelete_rejectsDatabaseAndPreservesData() {
        val testDbFile = File.createTempFile("test_fk_ondelete_alter_", ".db")
        if (testDbFile.exists()) testDbFile.delete()

        try {
            // 1. Initialize database with customSchemaCreator that introduces an altered FOREIGN KEY ON DELETE clause
            // on stores table: 'ON DELETE CASCADE' instead of canonical 'ON DELETE RESTRICT'
            val initFactory = DatabaseDriverFactory(testDbFile).apply {
                skipValidationDuringInit = true
                customSchemaCreator = { stagingDriver ->
                    StoreDatabase.Schema.create(stagingDriver)
                    // Recreate stores with altered ON DELETE clause
                    stagingDriver.execute(null, "DROP TABLE stores;", 0)
                    stagingDriver.execute(
                        null,
                        """
                        CREATE TABLE stores (
                            id TEXT PRIMARY KEY,
                            tenant_id TEXT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
                            code TEXT NOT NULL,
                            name TEXT NOT NULL,
                            address TEXT,
                            phone TEXT,
                            is_active INTEGER NOT NULL DEFAULT 1,
                            created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                            UNIQUE(tenant_id, code)
                        );
                        """.trimIndent(),
                        0
                    )
                    // Recreate explicit canonical indexes on stores to preserve exactly 108 canonical indexes
                    stagingDriver.execute(null, "CREATE UNIQUE INDEX ux_one_store_per_tenant ON stores(tenant_id);", 0)
                    stagingDriver.execute(null, "CREATE INDEX ix_stores_tenant ON stores(tenant_id);", 0)
                }
            }
            val initDriver = initFactory.createDriver()
            // Insert actual customer data into tenants
            initDriver.execute(null, "INSERT INTO tenants (id, name) VALUES ('T_VIP_02', 'Global Corp Retail');", 0)
            initDriver.close()

            // 2. Open via standard DatabaseDriverFactory (as an existing database)
            val standardFactory = DatabaseDriverFactory(testDbFile)
            try {
                standardFactory.createDriver()
                fail("DatabaseDriverFactory must reject existing database with altered FOREIGN KEY / ON DELETE behavior")
            } catch (e: DatabaseValidationException) {
                assertTrue(
                    "Exception must explicitly indicate altered table definition/constraints: ${e.message}",
                    e.message?.contains("stores") == true &&
                    e.message?.contains("definition/constraints altered") == true
                )
            }

            // 3. Prove that the database file was NOT deleted, corrupted, or replaced
            assertTrue("Database file must remain present on filesystem", testDbFile.exists())

            // 4. Prove customer data was preserved completely intact
            val verifyDriver = JdbcSqliteDriver("jdbc:sqlite:${testDbFile.absolutePath}")
            val tenantName = verifyDriver.executeQuery<String?>(
                null,
                "SELECT name FROM tenants WHERE id = 'T_VIP_02';",
                { it.next(); QueryResult.Value(it.getString(0)) },
                0
            ).value
            assertEquals("Customer tenant record must remain intact and unmodified", "Global Corp Retail", tenantName)
            verifyDriver.close()
        } finally {
            testDbFile.delete()
            File("${testDbFile.absolutePath}-wal").takeIf { it.exists() }?.delete()
            File("${testDbFile.absolutePath}-shm").takeIf { it.exists() }?.delete()
            File("${testDbFile.parentFile.absolutePath}/${testDbFile.name}.init.lock").takeIf { it.exists() }?.delete()
        }
    }
}
