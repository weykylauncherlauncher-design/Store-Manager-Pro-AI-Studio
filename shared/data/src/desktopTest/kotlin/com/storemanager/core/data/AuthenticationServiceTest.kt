package com.storemanager.core.data

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class AuthenticationServiceTest {
    private lateinit var tempDbFile: File
    private lateinit var driver: SqlDriver
    private lateinit var database: StoreDatabase
    private lateinit var passwordHash: String
    private lateinit var passwordSalt: String

    @Before
    fun setUp() {
        runBlocking {
            tempDbFile = File.createTempFile("test_authentication_", ".db")
            tempDbFile.delete()
            driver = DatabaseDriverFactory(tempDbFile).createDriver()
            database = StoreDatabase(driver)

            database.storeDatabaseQueries.apply {
                insertTenant("TENANT_AUTH_A", "Authentication Tenant A", 1)
                insertTenant("TENANT_AUTH_B", "Authentication Tenant B", 1)
                insertStore("STORE_AUTH_A", "TENANT_AUTH_A", "Store A", "A")
                insertStore("STORE_AUTH_B", "TENANT_AUTH_B", "Store B", "B")
                insertRole("ROLE_AUTH_SALES", "TENANT_AUTH_A", "Sales", null, 0)
                insertRole("ROLE_AUTH_REPORTS", "TENANT_AUTH_A", "Reports", null, 0)
                insertRole("ROLE_AUTH_OTHER", "TENANT_AUTH_B", "Other tenant", null, 0)

                insertPermission("SALE_CREATE", "SALES", "Create and complete sales")
                insertPermission("REPORT_VIEW", "REPORTS", "View reports")
                insertRolePermission("ROLE_AUTH_SALES", "SALE_CREATE")
                insertRolePermission("ROLE_AUTH_REPORTS", "REPORT_VIEW")
            }

            val credentials = Argon2idPasswordHasher().createCredentials(TEST_PASSWORD.toCharArray())
            passwordHash = credentials.passwordHash
            passwordSalt = credentials.salt

            database.storeDatabaseQueries.apply {
                insertUser(
                    "USER_AUTH_ACTIVE",
                    "TENANT_AUTH_A",
                    "active-user",
                    passwordHash,
                    passwordSalt,
                    "Active User",
                    1
                )
                insertUserRole("USER_AUTH_ACTIVE", "ROLE_AUTH_SALES")
                insertUserRole("USER_AUTH_ACTIVE", "ROLE_AUTH_REPORTS")

                insertUser(
                    "USER_AUTH_DISABLED",
                    "TENANT_AUTH_A",
                    "disabled-user",
                    passwordHash,
                    passwordSalt,
                    "Disabled User",
                    0
                )
                insertUser(
                    "USER_AUTH_MALFORMED",
                    "TENANT_AUTH_A",
                    "malformed-user",
                    "corrupt-hash",
                    passwordSalt,
                    "Malformed User",
                    1
                )
                insertUser(
                    "USER_AUTH_OTHER",
                    "TENANT_AUTH_B",
                    "only-in-other-tenant",
                    passwordHash,
                    passwordSalt,
                    "Other Tenant User",
                    1
                )
                insertUserRole("USER_AUTH_OTHER", "ROLE_AUTH_OTHER")
            }
        }
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
    fun authenticatesWithinTenantLoadsAssignedAccessAndDoesNotMutateDatabase() = runBlocking {
        val service = AuthenticationService(database)
        val countsBefore = recordCounts()

        val correctPassword = TEST_PASSWORD.toCharArray()
        val session = service.authenticate(
            "TENANT_AUTH_A",
            "STORE_AUTH_A",
            "active-user",
            correctPassword
        )

        assertTrue(correctPassword.all { it == '\u0000' })
        assertEquals("USER_AUTH_ACTIVE", session.userId)
        assertEquals("TENANT_AUTH_A", session.tenantId)
        assertEquals("STORE_AUTH_A", session.storeId)
        assertEquals("active-user", session.username)
        assertEquals("Active User", session.fullName)
        assertEquals(setOf("ROLE_AUTH_SALES", "ROLE_AUTH_REPORTS"), session.roleIds)
        assertEquals(setOf("SALE_CREATE", "REPORT_VIEW"), session.permissionCodes)
        assertFalse(session.permissionCodes.contains("INVENTORY_ADJUST"))
        assertFalse(session.javaClass.declaredFields.any {
            it.name.contains("password", ignoreCase = true) ||
                it.name.contains("salt", ignoreCase = true) ||
                it.name.contains("hash", ignoreCase = true)
        })

        val wrongPassword = "wrong-password".toCharArray()
        val wrongPasswordFailure = assertAuthenticationFailure {
            service.authenticate("TENANT_AUTH_A", "STORE_AUTH_A", "active-user", wrongPassword)
        }
        assertTrue(wrongPassword.all { it == '\u0000' })

        val missingUserPassword = TEST_PASSWORD.toCharArray()
        val missingUserFailure = assertAuthenticationFailure {
            service.authenticate("TENANT_AUTH_A", "STORE_AUTH_A", "missing-user", missingUserPassword)
        }
        assertTrue(missingUserPassword.all { it == '\u0000' })

        val disabledUserPassword = TEST_PASSWORD.toCharArray()
        val disabledUserFailure = assertAuthenticationFailure {
            service.authenticate("TENANT_AUTH_A", "STORE_AUTH_A", "disabled-user", disabledUserPassword)
        }
        assertTrue(disabledUserPassword.all { it == '\u0000' })

        val crossTenantPassword = TEST_PASSWORD.toCharArray()
        val crossTenantFailure = assertAuthenticationFailure {
            service.authenticate(
                "TENANT_AUTH_A",
                "STORE_AUTH_A",
                "only-in-other-tenant",
                crossTenantPassword
            )
        }
        assertTrue(crossTenantPassword.all { it == '\u0000' })

        val malformedPassword = TEST_PASSWORD.toCharArray()
        val malformedCredentialFailure = assertAuthenticationFailure {
            service.authenticate(
                "TENANT_AUTH_A",
                "STORE_AUTH_A",
                "malformed-user",
                malformedPassword
            )
        }
        assertTrue(malformedPassword.all { it == '\u0000' })

        val failures = listOf(
            wrongPasswordFailure,
            missingUserFailure,
            disabledUserFailure,
            crossTenantFailure,
            malformedCredentialFailure
        )
        assertTrue(failures.all { it.javaClass == AuthenticationFailedException::class.java })
        assertEquals(1, failures.map { it.message }.distinct().size)
        assertEquals(countsBefore, recordCounts())
        assertEquals(
            passwordHash,
            database.storeDatabaseQueries
                .selectUserByTenantAndUsername("TENANT_AUTH_A", "active-user")
                .executeAsOne()
                .password_hash
        )
    }

    private fun assertAuthenticationFailure(
        block: suspend () -> AuthenticatedSession
    ): AuthenticationFailedException =
        assertThrows(AuthenticationFailedException::class.java) {
            runBlocking { block() }
        }

    private fun recordCounts(): Map<String, Long> {
        val queries = database.storeDatabaseQueries
        return mapOf(
            "tenants" to queries.selectAllTenants().executeAsList().size.toLong(),
            "stores" to queries.selectAllStores().executeAsList().size.toLong(),
            "users" to queries.selectAllUsers().executeAsList().size.toLong(),
            "units" to queries.selectUnitsOfMeasure().executeAsList().size.toLong(),
            "permissions" to queries.selectAllPermissions().executeAsList().size.toLong(),
            "roles" to queries.selectRolesByTenantId("TENANT_AUTH_A").executeAsList().size.toLong(),
            "userRoles" to queries.selectRoleIdsByUserId("USER_AUTH_ACTIVE").executeAsList().size.toLong(),
            "rolePermissions" to queries.selectPermissionCodesByRoleId("ROLE_AUTH_SALES")
                .executeAsList().size.toLong()
        )
    }

    private companion object {
        const val TEST_PASSWORD = "temporary-auth-test-password"
    }
}
