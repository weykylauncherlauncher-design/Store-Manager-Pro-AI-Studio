package com.storemanager.pos

import com.storemanager.core.data.DatabaseDriverFactory
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
class AndroidIsolationRobolectricTest {

    @Test
    fun testAndroidPOS_directDatabaseAccessForbidden() {
        val factory = DatabaseDriverFactory()
        try {
            factory.createDriver()
            fail("Android runtime must never open or create master operational SQLite database")
        } catch (e: UnsupportedOperationException) {
            assertTrue(
                "Must provide clear architecture rejection message",
                e.message?.contains("LAN HTTPS") == true && e.message?.contains("Android does not open or write") == true
            )
        }
    }
}
