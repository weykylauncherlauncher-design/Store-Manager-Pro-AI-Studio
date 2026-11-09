package com.storemanager.core.data

object PermissionCodes {
    const val SALE_CREATE = "SALE_CREATE"
    const val INVENTORY_ADJUST = "INVENTORY_ADJUST"
    const val PAYMENT_CREATE = "PAYMENT_CREATE"
    const val REPORT_VIEW = "REPORT_VIEW"

    const val PRODUCT_VIEW = "PRODUCT_VIEW"
    const val PRODUCT_CREATE = "PRODUCT_CREATE"
    const val PRODUCT_UPDATE = "PRODUCT_UPDATE"
    const val PRODUCT_DEACTIVATE = "PRODUCT_DEACTIVATE"

    const val CATEGORY_CREATE = "CATEGORY_CREATE"
    const val CATEGORY_UPDATE = "CATEGORY_UPDATE"
    const val CATEGORY_DEACTIVATE = "CATEGORY_DEACTIVATE"

    internal val known = setOf(
        SALE_CREATE,
        INVENTORY_ADJUST,
        PAYMENT_CREATE,
        REPORT_VIEW,
        PRODUCT_VIEW,
        PRODUCT_CREATE,
        PRODUCT_UPDATE,
        PRODUCT_DEACTIVATE,
        CATEGORY_CREATE,
        CATEGORY_UPDATE,
        CATEGORY_DEACTIVATE
    )
}

class AuthorizationException : Exception("Permission denied")

object PermissionGuard {
    fun hasPermission(session: AuthenticatedSession, permissionCode: String): Boolean =
        permissionCode in PermissionCodes.known &&
            permissionCode in session.permissionCodes

    fun requirePermission(session: AuthenticatedSession, permissionCode: String) {
        if (!hasPermission(session, permissionCode)) {
            throw AuthorizationException()
        }
    }
}
