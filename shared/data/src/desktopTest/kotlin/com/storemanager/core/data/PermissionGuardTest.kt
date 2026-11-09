package com.storemanager.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionGuardTest {
    @Test
    fun allowsPermissionPresentInSession() {
        val session = session(permissionCodes = setOf(PermissionCodes.PRODUCT_VIEW))

        assertTrue(PermissionGuard.hasPermission(session, PermissionCodes.PRODUCT_VIEW))
        PermissionGuard.requirePermission(session, PermissionCodes.PRODUCT_VIEW)
    }

    @Test
    fun rejectsMissingAndUnknownPermissions() {
        val session = session(
            permissionCodes = setOf(PermissionCodes.PRODUCT_VIEW, "UNKNOWN_PERMISSION")
        )

        assertFalse(PermissionGuard.hasPermission(session, PermissionCodes.PRODUCT_CREATE))
        assertFalse(PermissionGuard.hasPermission(session, "UNKNOWN_PERMISSION"))
        assertThrows(AuthorizationException::class.java) {
            PermissionGuard.requirePermission(session, PermissionCodes.PRODUCT_CREATE)
        }
        assertThrows(AuthorizationException::class.java) {
            PermissionGuard.requirePermission(session, "UNKNOWN_PERMISSION")
        }
    }

    @Test
    fun administratorReceivesOnlyPermissionsActuallyPresentInSession() {
        val productPermissions = setOf(
            PermissionCodes.PRODUCT_VIEW,
            PermissionCodes.PRODUCT_CREATE,
            PermissionCodes.PRODUCT_UPDATE,
            PermissionCodes.PRODUCT_DEACTIVATE
        )
        val administrator = session(
            roleIds = setOf("ROLE_ADMINISTRATOR"),
            permissionCodes = productPermissions
        )

        productPermissions.forEach { PermissionGuard.requirePermission(administrator, it) }

        val administratorWithoutPermissions = session(
            roleIds = setOf("Administrator"),
            permissionCodes = emptySet()
        )
        assertFalse(
            PermissionGuard.hasPermission(
                administratorWithoutPermissions,
                PermissionCodes.PRODUCT_VIEW
            )
        )
    }

    @Test
    fun cashierAndStockManagerCannotWriteProducts() {
        val writePermissions = setOf(
            PermissionCodes.PRODUCT_CREATE,
            PermissionCodes.PRODUCT_UPDATE,
            PermissionCodes.PRODUCT_DEACTIVATE
        )
        listOf("ROLE_CASHIER", "ROLE_STOCK_MANAGER").forEach { roleId ->
            val session = session(
                roleIds = setOf(roleId),
                permissionCodes = setOf(PermissionCodes.PRODUCT_VIEW)
            )
            writePermissions.forEach { permission ->
                assertFalse(PermissionGuard.hasPermission(session, permission))
                assertThrows(AuthorizationException::class.java) {
                    PermissionGuard.requirePermission(session, permission)
                }
            }
        }
    }

    @Test
    fun categoryManagementPermissionsAreRecognizedButNotImpliedByRoles() {
        val categoryPermissions = setOf(
            PermissionCodes.CATEGORY_CREATE,
            PermissionCodes.CATEGORY_UPDATE,
            PermissionCodes.CATEGORY_DEACTIVATE
        )
        val administrator = session(
            roleIds = setOf("ROLE_ADMINISTRATOR"),
            permissionCodes = categoryPermissions
        )
        categoryPermissions.forEach { PermissionGuard.requirePermission(administrator, it) }

        listOf("ROLE_CASHIER", "ROLE_STOCK_MANAGER", "ROLE_CUSTOM").forEach { roleId ->
            val roleSession = session(
                roleIds = setOf(roleId),
                permissionCodes = emptySet()
            )
            categoryPermissions.forEach { permission ->
                assertFalse(PermissionGuard.hasPermission(roleSession, permission))
                assertThrows(AuthorizationException::class.java) {
                    PermissionGuard.requirePermission(roleSession, permission)
                }
            }
        }
    }

    @Test
    fun emptyPermissionsDenyByDefaultAndExceptionDoesNotExposeCredentials() {
        val session = session(permissionCodes = emptySet())
        val failure = assertThrows(AuthorizationException::class.java) {
            PermissionGuard.requirePermission(session, PermissionCodes.PRODUCT_VIEW)
        }

        assertFalse(PermissionGuard.hasPermission(session, PermissionCodes.PRODUCT_VIEW))
        assertEquals("Permission denied", failure.message)
        assertFalse(failure.message.orEmpty().contains("test-password-secret"))
        assertFalse(failure.message.orEmpty().contains("test-hash-secret"))
        assertFalse(failure.message.orEmpty().contains("test-salt-secret"))
    }

    private fun session(
        roleIds: Set<String> = emptySet(),
        permissionCodes: Set<String>
    ) = AuthenticatedSession(
        userId = "USER_PERMISSION_TEST",
        tenantId = "TENANT_PERMISSION_TEST",
        storeId = "STORE_PERMISSION_TEST",
        username = "permission-test-user",
        fullName = "Permission Test User",
        roleIds = roleIds,
        permissionCodes = permissionCodes
    )
}
