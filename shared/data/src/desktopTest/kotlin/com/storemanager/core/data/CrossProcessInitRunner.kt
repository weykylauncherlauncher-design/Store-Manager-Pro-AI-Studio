package com.storemanager.core.data

import app.cash.sqldelight.db.QueryResult
import java.io.File

object CrossProcessInitRunner {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            System.err.println("Usage: CrossProcessInitRunner <targetDbPath> [initDelayMs]")
            System.exit(1)
        }
        val targetPath = args[0]
        val delayMs = if (args.size > 1) args[1].toLongOrNull() ?: 0L else 0L

        val targetFile = File(targetPath)
        val factory = DatabaseDriverFactory(targetFile).apply {
            if (delayMs > 0L) {
                initDelayMs = delayMs
            }
        }

        try {
            val driver = factory.createDriver()
            val tableCount = driver.executeQuery<Long>(
                null,
                "SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%';",
                { it.next(); QueryResult.Value(it.getLong(0) ?: 0L) },
                0
            ).value

            val indexCount = driver.executeQuery<Long>(
                null,
                "SELECT count(*) FROM sqlite_master WHERE type='index' AND sql IS NOT NULL;",
                { it.next(); QueryResult.Value(it.getLong(0) ?: 0L) },
                0
            ).value

            val triggerCount = driver.executeQuery<Long>(
                null,
                "SELECT count(*) FROM sqlite_master WHERE type='trigger';",
                { it.next(); QueryResult.Value(it.getLong(0) ?: 0L) },
                0
            ).value

            println("PROCESS_RESULT:SUCCESS:tables=$tableCount,indexes=$indexCount,triggers=$triggerCount")
            driver.close()
            System.exit(0)
        } catch (e: Throwable) {
            System.err.println("PROCESS_RESULT:FAILURE:${e.message}")
            e.printStackTrace()
            System.exit(2)
        }
    }
}
