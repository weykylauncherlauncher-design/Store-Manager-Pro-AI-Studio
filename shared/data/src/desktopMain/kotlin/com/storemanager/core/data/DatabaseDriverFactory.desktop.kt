package com.storemanager.core.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ColumnSchemaInfo(
    val name: String,
    val type: String,
    val notnull: Long,
    val defaultValue: String?,
    val pk: Long
)

data class SchemaObjectDefinition(
    val type: String,
    val name: String,
    val tblName: String,
    val sql: String
)

class CanonicalSchemaBaseline(
    val tableNames: List<String>,
    val tableDefinitions: Map<String, SchemaObjectDefinition>,
    val tableColumns: Map<String, List<ColumnSchemaInfo>>,
    val indexes: Map<String, SchemaObjectDefinition>,
    val triggers: Map<String, SchemaObjectDefinition>,
    val fingerprint: String
)

actual class DatabaseDriverFactory(
    private val dbFile: File? = null
) {
    internal var simulateTriggerFailure: Boolean = false
    internal var checkpointResultOverride: Triple<Long, Long, Long>? = null
    internal var simulateStagingWalSidecarFailure: Boolean = false
    internal var simulateSidecarCleanupFailure: Boolean = false
    internal var sidecarDeleter: ((File) -> Boolean)? = null
    internal var walSidecarDeleter: ((File) -> Boolean)? = null
    internal var shmSidecarDeleter: ((File) -> Boolean)? = null
    internal var onBeforeAtomicMove: ((stagingFile: File, stagingWal: File, stagingShm: File) -> Unit)? = null
    internal var simulateTargetCollisionDuringMove: Boolean = false
    internal var simulateTargetCollision0Byte: Boolean = false
    internal var customTriggerScript: String? = null
    internal var customSchemaCreator: ((SqlDriver) -> Unit)? = null
    internal var skipValidationDuringInit: Boolean = false
    internal var simulateMigrationFailure: Boolean = false
    internal var initDelayMs: Long = 0L
    internal var driverWrapper: ((SqlDriver) -> SqlDriver)? = null

    actual fun createDriver(): SqlDriver {
        val targetFile = dbFile ?: getDefaultDatabaseFile()
        val parentDir = targetFile.parentFile ?: File(".")
        if (!parentDir.exists()) {
            parentDir.mkdirs()
        }

        val canonicalPath = targetFile.canonicalPath
        val jvmLock = inJvmLocks.computeIfAbsent(canonicalPath) { Any() }
        val isNewDatabase: Boolean

        // Thread-safe within JVM + Process-safe across OS processes via FileChannel.lock
        synchronized(jvmLock) {
            val lockFile = File(parentDir, "${targetFile.name}.init.lock")
            RandomAccessFile(lockFile, "rw").use { raf ->
                raf.channel.lock().use {
                    // Check target existence strictly after acquiring exclusive lock
                    if (!targetFile.exists()) {
                        isNewDatabase = true
                        initializeNewDatabaseAtomically(targetFile, parentDir)
                    } else {
                        isNewDatabase = false
                    }
                }
            }
        }

        // Open connection to verified target database
        val properties = createConnectionProperties()
        val url = "jdbc:sqlite:${targetFile.absolutePath}"
        val driver = JdbcSqliteDriver(url, properties)

        // Apply per-connection runtime PRAGMAs
        applyConnectionRuntimePragmas(driver)

        if (!isNewDatabase) {
            // Validate existing database before modifying any persistent database properties
            try {
                validateExistingDatabase(driver)
                // Only after validation succeeds, ensure persistent journal_mode is WAL
                driver.execute(null, "PRAGMA journal_mode = WAL;", 0)
            } catch (e: Throwable) {
                try { driver.close() } catch (_: Throwable) {}
                // Never delete, wipe, or alter existing file on validation rejection
                throw e
            }
        }

        return driverWrapper?.invoke(driver) ?: driver
    }

    private fun createConnectionProperties(): Properties {
        return Properties().apply {
            put("foreign_keys", "true")
            put("busy_timeout", "10000") // 10 seconds busy timeout
            put("synchronous", "FULL")   // Full synchronous durability for financial and inventory safety
        }
    }

    private fun applyConnectionRuntimePragmas(driver: SqlDriver) {
        driver.execute(null, "PRAGMA foreign_keys = ON;", 0)
        driver.execute(null, "PRAGMA busy_timeout = 10000;", 0)
        driver.execute(null, "PRAGMA synchronous = FULL;", 0)
    }

    private fun initializeNewDatabaseAtomically(targetFile: File, parentDir: File) {
        if (initDelayMs > 0L) {
            Thread.sleep(initDelayMs)
        }

        // Staging file is created strictly in the SAME directory as the target database
        val stagingFile = File(
            parentDir,
            "${targetFile.name}.staging.${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        )

        var stagingDriver: SqlDriver? = null
        try {
            val properties = Properties().apply {
                put("foreign_keys", "true")
                put("busy_timeout", "10000")
                put("journal_mode", "WAL")
                put("synchronous", "FULL")
            }
            val stagingUrl = "jdbc:sqlite:${stagingFile.absolutePath}"
            stagingDriver = JdbcSqliteDriver(stagingUrl, properties)

            // Configure staging connection
            stagingDriver.execute(null, "PRAGMA foreign_keys = ON;", 0)
            stagingDriver.execute(null, "PRAGMA busy_timeout = 10000;", 0)
            stagingDriver.execute(null, "PRAGMA journal_mode = WAL;", 0)
            stagingDriver.execute(null, "PRAGMA synchronous = FULL;", 0)

            // 1. Create the current schema and explicit indexes from SQLDelight
            if (customSchemaCreator != null) {
                customSchemaCreator!!.invoke(stagingDriver)
            } else {
                StoreDatabase.Schema.create(stagingDriver)
            }

            // 2. Execute canonical triggers
            if (simulateTriggerFailure) {
                val script = customTriggerScript ?: loadCanonicalTriggerScript()
                val parts = script.split("CREATE TRIGGER").filter { it.trim().isNotBlank() }
                for (i in 0 until (parts.size / 2).coerceAtLeast(1)) {
                    val statement = "CREATE TRIGGER " + parts[i].trim()
                    stagingDriver.execute(null, statement, 0)
                }
                throw IllegalStateException("Simulated mid-trigger failure during database initialization")
            } else {
                val script = customTriggerScript ?: loadCanonicalTriggerScript()
                executeTriggerScript(stagingDriver, script)
            }

            // 3. Validate newly initialized staging schema before setting user_version
            if (!skipValidationDuringInit) {
                validateV3Database(stagingDriver)
            }

            // 4. Mark database baseline version
            stagingDriver.execute(null, "PRAGMA user_version = $EXPECTED_USER_VERSION;", 0)
            if (!skipValidationDuringInit) {
                validateExistingDatabase(stagingDriver)
            }

            // 5. WAL Checkpoint Verification: read checkpoint results and verify no busy frames remain
            val checkpointResult = queryCheckpoint(stagingDriver)
            val busy = checkpointResult.first
            val log = checkpointResult.second
            val checkpointed = checkpointResult.third

            if (busy != 0L) {
                throw DatabaseInitializationException("WAL checkpoint blocked: busy flag = $busy")
            }
            if (checkpointed < log) {
                throw DatabaseInitializationException("WAL checkpoint incomplete: checkpointed $checkpointed of $log frames")
            }

            // Close staging driver cleanly before publication
            stagingDriver.close()
            stagingDriver = null

            // 6. Verify sidecar files: no non-empty WAL/SHM files may remain
            val stagingWal = File("${stagingFile.absolutePath}-wal")
            val stagingShm = File("${stagingFile.absolutePath}-shm")
            if (simulateStagingWalSidecarFailure) {
                stagingWal.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
            }
            val activeWalDeleter = walSidecarDeleter ?: sidecarDeleter
            if (stagingWal.exists() || activeWalDeleter != null) {
                if (stagingWal.exists() && stagingWal.length() > 0L) {
                    throw DatabaseInitializationException("Staging WAL sidecar contains uncheckpointed data (${stagingWal.length()} bytes)")
                }
                val deleted = activeWalDeleter?.invoke(stagingWal) ?: stagingWal.delete()
                if (!deleted && (stagingWal.exists() || activeWalDeleter != null)) {
                    throw DatabaseInitializationException("Failed to remove staging WAL sidecar before publication")
                }
            }
            val activeShmDeleter = shmSidecarDeleter ?: sidecarDeleter
            if (stagingShm.exists() || activeShmDeleter != null) {
                val deleted = activeShmDeleter?.invoke(stagingShm) ?: stagingShm.delete()
                if (!deleted && (stagingShm.exists() || activeShmDeleter != null)) {
                    throw DatabaseInitializationException("Failed to remove staging SHM sidecar before publication")
                }
            }

            // Verify before move
            onBeforeAtomicMove?.invoke(stagingFile, stagingWal, stagingShm)

            // 7. Secure Atomic Publication:
            // Check collision: Never replace an existing database file under any circumstance
            if (simulateTargetCollisionDuringMove) {
                if (simulateTargetCollision0Byte) {
                    targetFile.writeText("")
                } else {
                    targetFile.writeText("PRE_EXISTING_TARGET_FILE_MARKER")
                }
            }
            if (targetFile.exists()) {
                throw DatabaseInitializationException(
                    "Publication aborted: target database file already exists at ${targetFile.absolutePath}. Replacing existing databases is strictly prohibited."
                )
            }

            // Move using ATOMIC_MOVE strictly — NO fallback to REPLACE_EXISTING
            try {
                Files.move(
                    stagingFile.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (e: AtomicMoveNotSupportedException) {
                throw DatabaseInitializationException(
                    "Filesystem does not support ATOMIC_MOVE across target path. Staging aborted to protect integrity.",
                    e
                )
            } catch (e: Exception) {
                throw DatabaseInitializationException(
                    "Atomic publication failed: ${e.message}",
                    e
                )
            }
        } catch (e: Throwable) {
            // Safely close staging driver
            try { stagingDriver?.close() } catch (_: Throwable) {}
            // Clean up staging files completely
            cleanFileAndSidecars(stagingFile)
            // CRITICAL: The initialization cleanup routine must never delete, replace, or modify an existing target file
            throw DatabaseInitializationException(
                "Database initialization failed and staging was cleaned up: ${e.message}",
                e
            )
        }
    }

    private fun queryCheckpoint(driver: SqlDriver): Triple<Long, Long, Long> {
        checkpointResultOverride?.let { return it }
        val cursor = driver.executeQuery<Triple<Long, Long, Long>>(
            null,
            "PRAGMA wal_checkpoint(TRUNCATE);",
            {
                it.next()
                val busy = it.getLong(0) ?: 0L
                val log = it.getLong(1) ?: 0L
                val checkpointed = it.getLong(2) ?: 0L
                QueryResult.Value(Triple(busy, log, checkpointed))
            },
            0
        )
        return cursor.value
    }

    private fun loadCanonicalTriggerScript(): String {
        val stream = this::class.java.classLoader.getResourceAsStream("canonical_triggers.sql")
            ?: error("canonical_triggers.sql resource not found in classpath")
        return stream.bufferedReader().use { it.readText() }
    }

    private fun executeTriggerScript(driver: SqlDriver, script: String) {
        val parts = script.split("CREATE TRIGGER")
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.isNotBlank()) {
                val statement = "CREATE TRIGGER " + trimmed
                driver.execute(null, statement, 0)
            }
        }
    }

    private fun cleanFileAndSidecars(baseFile: File) {
        if (baseFile.exists()) baseFile.delete()
        File("${baseFile.absolutePath}-wal").takeIf { it.exists() }?.delete()
        File("${baseFile.absolutePath}-shm").takeIf { it.exists() }?.delete()
    }

    fun validateExistingDatabase(driver: SqlDriver) {
        when (val userVersion = querySingleLong(driver, "PRAGMA user_version;")) {
            LEGACY_USER_VERSION -> {
                validateDatabaseSchema(
                    driver = driver,
                    baseline = v1CanonicalBaseline,
                    tableCount = V1_TABLE_COUNT,
                    indexCount = V1_INDEX_COUNT,
                    triggerCount = V1_TRIGGER_COUNT
                )
                migrateV1ToV2(driver)
                migrateV2ToV3(driver)
            }
            V2_USER_VERSION -> {
                validateV2Database(driver)
                migrateV2ToV3(driver)
            }
            EXPECTED_USER_VERSION -> validateV3Database(driver)
            else -> throw DatabaseValidationException(
                "Invalid database user_version: found $userVersion, expected $LEGACY_USER_VERSION, $V2_USER_VERSION or $EXPECTED_USER_VERSION. Database rejected."
            )
        }
    }

    private fun migrateV1ToV2(driver: SqlDriver) {
        try {
            StoreDatabase(driver).transaction {
                StoreDatabase.Schema.migrate(driver, LEGACY_USER_VERSION, V2_USER_VERSION)
                if (this@DatabaseDriverFactory.simulateMigrationFailure) {
                    throw DatabaseValidationException("Simulated Schema v1 to v2 migration failure")
                }

                validateV2Database(driver)
                driver.execute(null, "PRAGMA user_version = $V2_USER_VERSION;", 0)
                if (querySingleLong(driver, "PRAGMA user_version;") != V2_USER_VERSION) {
                    throw DatabaseValidationException("Schema v1 to v2 migration did not set the expected user_version")
                }
                validateV2Database(driver)
            }
        } catch (failure: Throwable) {
            if (failure is DatabaseValidationException) throw failure
            throw DatabaseValidationException(
                "Schema v1 to v2 migration failed; the transaction was rolled back.",
                failure
            )
        }
    }

    private fun migrateV2ToV3(driver: SqlDriver) {
        try {
            StoreDatabase(driver).transaction {
                TenantScopedSkuMigration(driver, loadCanonicalTriggerScript()).migrate()
                if (this@DatabaseDriverFactory.simulateMigrationFailure) {
                    throw DatabaseValidationException("Simulated Schema v2 to v3 migration failure")
                }
                validateV3Database(driver)
                driver.execute(null, "PRAGMA user_version = $EXPECTED_USER_VERSION;", 0)
                if (querySingleLong(driver, "PRAGMA user_version;") != EXPECTED_USER_VERSION) {
                    throw DatabaseValidationException("Schema v2 to v3 migration did not set the expected user_version")
                }
                validateV3Database(driver)
            }
        } catch (failure: Throwable) {
            if (failure is DatabaseValidationException) throw failure
            throw DatabaseValidationException(
                "Schema v2 to v3 migration failed; the transaction was rolled back.",
                failure
            )
        }
    }

    private fun validateV2Database(driver: SqlDriver) {
        validateDatabaseSchema(
            driver = driver,
            baseline = v2CanonicalBaseline,
            tableCount = V2_TABLE_COUNT,
            indexCount = V2_INDEX_COUNT,
            triggerCount = V2_TRIGGER_COUNT
        )
    }

    private fun validateV3Database(driver: SqlDriver) {
        validateDatabaseSchema(
            driver = driver,
            baseline = canonicalBaseline,
            tableCount = EXPECTED_TABLE_COUNT,
            indexCount = EXPECTED_INDEX_COUNT,
            triggerCount = EXPECTED_TRIGGER_COUNT
        )
    }

    private fun validateDatabaseSchema(
        driver: SqlDriver,
        baseline: CanonicalSchemaBaseline,
        tableCount: Long,
        indexCount: Long,
        triggerCount: Long
    ) {
        validateCountsAndIntegrity(driver, tableCount, indexCount, triggerCount)
        validateExactSchema(driver, baseline, tableCount, indexCount, triggerCount)
    }

    private fun validateCountsAndIntegrity(
        driver: SqlDriver,
        expectedTableCount: Long,
        expectedIndexCount: Long,
        expectedTriggerCount: Long
    ) {
        // Tables count
        val tableCount = querySingleLong(
            driver,
            "SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%';"
        )
        if (tableCount != expectedTableCount) {
            throw DatabaseValidationException(
                "Incomplete database schema: found $tableCount tables, expected $expectedTableCount."
            )
        }

        // Indexes count
        val indexCount = querySingleLong(
            driver,
            "SELECT count(*) FROM sqlite_master WHERE type='index' AND sql IS NOT NULL;"
        )
        if (indexCount != expectedIndexCount) {
            throw DatabaseValidationException(
                "Incomplete database schema: found $indexCount explicit indexes, expected $expectedIndexCount."
            )
        }

        // Triggers count
        val triggerCount = querySingleLong(
            driver,
            "SELECT count(*) FROM sqlite_master WHERE type='trigger';"
        )
        if (triggerCount != expectedTriggerCount) {
            throw DatabaseValidationException(
                "Incomplete database schema: found $triggerCount triggers, expected $expectedTriggerCount."
            )
        }

        // Integrity check
        val integrityResult = querySingleString(driver, "PRAGMA integrity_check;")
        if (integrityResult != "ok") {
            throw DatabaseValidationException(
                "Database integrity check failed: $integrityResult"
            )
        }

        // Foreign key check
        val fkErrors = countRows(driver, "PRAGMA foreign_key_check;")
        if (fkErrors > 0) {
            throw DatabaseValidationException(
                "Database foreign key check failed with $fkErrors violations."
            )
        }
    }

    fun validateExactSchema(driver: SqlDriver) {
        validateExactSchema(
            driver,
            canonicalBaseline,
            EXPECTED_TABLE_COUNT,
            EXPECTED_INDEX_COUNT,
            EXPECTED_TRIGGER_COUNT
        )
    }

    private fun validateExactSchema(
        driver: SqlDriver,
        baseline: CanonicalSchemaBaseline,
        expectedTableCount: Long,
        expectedIndexCount: Long,
        expectedTriggerCount: Long
    ) {
        // 1. Verify exact Table Names and Table CREATE Statements
        val targetTables = queryTableNames(driver)
        if (targetTables.size.toLong() != expectedTableCount) {
            throw DatabaseValidationException(
                "Schema validation failed: expected $expectedTableCount tables, found ${targetTables.size}"
            )
        }
        val missingTables = baseline.tableNames - targetTables.toSet()
        val extraTables = targetTables.toSet() - baseline.tableNames.toSet()
        if (missingTables.isNotEmpty() || extraTables.isNotEmpty()) {
            throw DatabaseValidationException(
                "Schema validation failed: table mismatch. Missing: $missingTables, Unexpected: $extraTables"
            )
        }
        val targetTableDefs = queryTableDefinitions(driver)
        for (tableName in baseline.tableNames) {
            val expDef = baseline.tableDefinitions[tableName]
                ?: throw DatabaseValidationException("Schema validation failed: missing baseline definition for table '$tableName'")
            val actDef = targetTableDefs[tableName]
                ?: throw DatabaseValidationException("Schema validation failed: missing table definition for '$tableName'")
            if (normalizeSql(actDef.sql) != normalizeSql(expDef.sql)) {
                throw DatabaseValidationException(
                    "Schema validation failed: table '$tableName' definition/constraints altered."
                )
            }
        }

        // 2. Verify Column Definitions & Constraints for each table
        for (tableName in baseline.tableNames) {
            val expectedCols = baseline.tableColumns[tableName] ?: emptyList()
            val actualCols = queryTableColumns(driver, tableName)
            if (actualCols.size != expectedCols.size) {
                throw DatabaseValidationException(
                    "Schema validation failed for table '$tableName': column count mismatch (expected ${expectedCols.size}, found ${actualCols.size})"
                )
            }
            for (i in expectedCols.indices) {
                val exp = expectedCols[i]
                val act = actualCols.getOrNull(i)
                if (act == null || act.name != exp.name || act.type != exp.type ||
                    act.notnull != exp.notnull || act.defaultValue != exp.defaultValue || act.pk != exp.pk
                ) {
                    throw DatabaseValidationException(
                        "Schema validation failed for table '$tableName': column '${exp.name}' mismatch (expected $exp, found $act)"
                    )
                }
            }
        }

        // 3. Verify exact Explicit Index names and definitions
        val targetIndexes = queryIndexes(driver)
        if (targetIndexes.size.toLong() != expectedIndexCount) {
            throw DatabaseValidationException(
                "Schema validation failed: expected $expectedIndexCount explicit indexes, found ${targetIndexes.size}"
            )
        }
        val missingIndexes = baseline.indexes.keys - targetIndexes.keys
        val extraIndexes = targetIndexes.keys - baseline.indexes.keys
        if (missingIndexes.isNotEmpty() || extraIndexes.isNotEmpty()) {
            throw DatabaseValidationException(
                "Schema validation failed: explicit index mismatch. Missing: $missingIndexes, Unexpected: $extraIndexes"
            )
        }
        for ((name, expIdx) in baseline.indexes) {
            val actIdx = targetIndexes[name]
                ?: throw DatabaseValidationException("Schema validation failed: missing index '$name'")
            if (actIdx.tblName != expIdx.tblName || normalizeSql(actIdx.sql) != normalizeSql(expIdx.sql)) {
                throw DatabaseValidationException(
                    "Schema validation failed: index '$name' definition altered."
                )
            }
        }

        // 4. Verify exact Trigger names and definitions
        val targetTriggers = queryTriggers(driver)
        if (targetTriggers.size.toLong() != expectedTriggerCount) {
            throw DatabaseValidationException(
                "Schema validation failed: expected $expectedTriggerCount triggers, found ${targetTriggers.size}"
            )
        }
        val missingTriggers = baseline.triggers.keys - targetTriggers.keys
        val extraTriggers = targetTriggers.keys - baseline.triggers.keys
        if (missingTriggers.isNotEmpty() || extraTriggers.isNotEmpty()) {
            throw DatabaseValidationException(
                "Schema validation failed: trigger mismatch. Missing: $missingTriggers, Unexpected: $extraTriggers"
            )
        }
        for ((name, expTrg) in baseline.triggers) {
            val actTrg = targetTriggers[name]
                ?: throw DatabaseValidationException("Schema validation failed: missing trigger '$name'")
            if (actTrg.tblName != expTrg.tblName || normalizeSql(actTrg.sql) != normalizeSql(expTrg.sql)) {
                throw DatabaseValidationException(
                    "Schema validation failed: trigger '$name' definition altered."
                )
            }
        }

        // 5. Verify Canonical Schema Fingerprint
        val targetFingerprint = computeSchemaFingerprint(targetTables, targetTableDefs, driver, targetIndexes, targetTriggers)
        if (targetFingerprint != baseline.fingerprint) {
            throw DatabaseValidationException(
                "Schema validation failed: canonical schema fingerprint mismatch (expected ${baseline.fingerprint}, found $targetFingerprint)"
            )
        }
    }

    private fun querySingleLong(driver: SqlDriver, sql: String): Long {
        val cursor = driver.executeQuery<Long>(
            null,
            sql,
            {
                it.next()
                QueryResult.Value(it.getLong(0) ?: 0L)
            },
            0
        )
        return cursor.value
    }

    private fun querySingleString(driver: SqlDriver, sql: String): String? {
        val cursor = driver.executeQuery<String?>(
            null,
            sql,
            {
                it.next()
                QueryResult.Value(it.getString(0))
            },
            0
        )
        return cursor.value
    }

    private fun countRows(driver: SqlDriver, sql: String): Long {
        val cursor = driver.executeQuery<Long>(
            null,
            sql,
            {
                var count = 0L
                while (it.next().value) {
                    count++
                }
                QueryResult.Value(count)
            },
            0
        )
        return cursor.value
    }

    companion object {
        private val inJvmLocks = ConcurrentHashMap<String, Any>()

        const val V1_TABLE_COUNT = 55L
        const val V1_INDEX_COUNT = 107L
        const val V1_TRIGGER_COUNT = 186L
        const val LEGACY_USER_VERSION = 1L
        const val V1_SCHEMA_FINGERPRINT = "530e443c4a198a7dc0705b502b8b8fc49b0fa71dc602875f58497de9e7fd08d4"

        const val V2_TABLE_COUNT = 56L
        const val V2_INDEX_COUNT = 108L
        const val V2_TRIGGER_COUNT = 186L
        const val V2_SCHEMA_FINGERPRINT = "562c9e435dce9efd31485831a2d2d84045ad80bd16b0bf82d858cccf96c022de"
        const val V2_USER_VERSION = 2L

        const val EXPECTED_TABLE_COUNT = 56L
        const val EXPECTED_INDEX_COUNT = 109L
        const val EXPECTED_TRIGGER_COUNT = 188L
        const val EXPECTED_USER_VERSION = 3L

        val canonicalBaseline: CanonicalSchemaBaseline by lazy {
            buildCanonicalBaseline()
        }

        private val v1CanonicalBaseline: CanonicalSchemaBaseline by lazy {
            buildV1CanonicalBaseline()
        }

        private val v2CanonicalBaseline: CanonicalSchemaBaseline by lazy {
            buildV2CanonicalBaseline()
        }

        fun normalizeSql(sql: String?): String {
            if (sql == null) return ""
            return sql.replace("\"", "").replace(Regex("\\s+"), " ").trim()
        }

        fun computeSha256(data: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(data.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun queryTableNames(driver: SqlDriver): List<String> {
            val cursor = driver.executeQuery<List<String>>(
                null,
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name;",
                {
                    val list = mutableListOf<String>()
                    while (it.next().value) {
                        list.add(it.getString(0)!!)
                    }
                    QueryResult.Value(list)
                },
                0
            )
            return cursor.value
        }

        fun queryTableColumns(driver: SqlDriver, tableName: String): List<ColumnSchemaInfo> {
            val cursor = driver.executeQuery<List<ColumnSchemaInfo>>(
                null,
                "PRAGMA table_info($tableName);",
                {
                    val colList = mutableListOf<ColumnSchemaInfo>()
                    while (it.next().value) {
                        val name = it.getString(1) ?: ""
                        val type = (it.getString(2) ?: "").uppercase()
                        val notnull = it.getLong(3) ?: 0L
                        val dflt = it.getString(4)
                        val pk = it.getLong(5) ?: 0L
                        colList.add(ColumnSchemaInfo(name, type, notnull, dflt, pk))
                    }
                    QueryResult.Value(colList)
                },
                0
            )
            return cursor.value
        }

        fun queryTableDefinitions(driver: SqlDriver): Map<String, SchemaObjectDefinition> {
            val cursor = driver.executeQuery<Map<String, SchemaObjectDefinition>>(
                null,
                "SELECT type, name, tbl_name, sql FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name;",
                {
                    val map = mutableMapOf<String, SchemaObjectDefinition>()
                    while (it.next().value) {
                        val type = it.getString(0) ?: "table"
                        val name = it.getString(1) ?: ""
                        val tblName = it.getString(2) ?: ""
                        val sql = it.getString(3) ?: ""
                        map[name] = SchemaObjectDefinition(type, name, tblName, sql)
                    }
                    QueryResult.Value(map)
                },
                0
            )
            return cursor.value
        }

        fun queryIndexes(driver: SqlDriver): Map<String, SchemaObjectDefinition> {
            val cursor = driver.executeQuery<Map<String, SchemaObjectDefinition>>(
                null,
                "SELECT type, name, tbl_name, sql FROM sqlite_master WHERE type='index' AND sql IS NOT NULL ORDER BY name;",
                {
                    val map = mutableMapOf<String, SchemaObjectDefinition>()
                    while (it.next().value) {
                        val type = it.getString(0) ?: "index"
                        val name = it.getString(1) ?: ""
                        val tblName = it.getString(2) ?: ""
                        val sql = it.getString(3) ?: ""
                        map[name] = SchemaObjectDefinition(type, name, tblName, sql)
                    }
                    QueryResult.Value(map)
                },
                0
            )
            return cursor.value
        }

        fun queryTriggers(driver: SqlDriver): Map<String, SchemaObjectDefinition> {
            val cursor = driver.executeQuery<Map<String, SchemaObjectDefinition>>(
                null,
                "SELECT type, name, tbl_name, sql FROM sqlite_master WHERE type='trigger' ORDER BY name;",
                {
                    val map = mutableMapOf<String, SchemaObjectDefinition>()
                    while (it.next().value) {
                        val type = it.getString(0) ?: "trigger"
                        val name = it.getString(1) ?: ""
                        val tblName = it.getString(2) ?: ""
                        val sql = it.getString(3) ?: ""
                        map[name] = SchemaObjectDefinition(type, name, tblName, sql)
                    }
                    QueryResult.Value(map)
                },
                0
            )
            return cursor.value
        }

        private fun computeSchemaFingerprint(
            tableNames: List<String>,
            tableDefinitions: Map<String, SchemaObjectDefinition>,
            driver: SqlDriver,
            indexes: Map<String, SchemaObjectDefinition>,
            triggers: Map<String, SchemaObjectDefinition>
        ): String {
            val rep = buildString {
                append("TABLES:")
                for (t in tableNames.sorted()) {
                    append("[$t:")
                    val tableSql = tableDefinitions[t]?.sql ?: ""
                    append(normalizeSql(tableSql))
                    append("|")
                    val cols = queryTableColumns(driver, t)
                    for (c in cols) {
                        // Keep null defaults compatible with the historical Python-generated v1 fingerprint.
                        append("(${c.name},${c.type},${c.notnull},${c.defaultValue ?: "None"},${c.pk})")
                    }
                    append("]")
                }
                append(";INDEXES:")
                for ((name, idx) in indexes.toSortedMap()) {
                    append("[$name:${idx.tblName}:${normalizeSql(idx.sql)}]")
                }
                append(";TRIGGERS:")
                for ((name, trg) in triggers.toSortedMap()) {
                    append("[$name:${trg.tblName}:${normalizeSql(trg.sql)}]")
                }
            }
            return computeSha256(rep)
        }

        private fun buildCanonicalBaseline(): CanonicalSchemaBaseline {
            val memDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            try {
                memDriver.execute(null, "PRAGMA foreign_keys = ON;", 0)
                StoreDatabase.Schema.create(memDriver)
                val factory = DatabaseDriverFactory()
                val triggerScript = factory.loadCanonicalTriggerScript()
                factory.executeTriggerScript(memDriver, triggerScript)

                return snapshotBaseline(memDriver)
            } finally {
                try { memDriver.close() } catch (_: Throwable) {}
            }
        }

        private fun buildV1CanonicalBaseline(): CanonicalSchemaBaseline {
            val classLoader = DatabaseDriverFactory::class.java.classLoader
            val snapshot = classLoader.getResourceAsStream("schema-baselines/v1/1.db")
                ?: throw DatabaseValidationException("Historical Schema v1 snapshot is missing")
            val tempFile = File.createTempFile("store-schema-v1-baseline-", ".db")
            try {
                snapshot.use { input -> Files.copy(input, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                val properties = Properties().apply {
                    put("foreign_keys", "true")
                    put("busy_timeout", "10000")
                    put("synchronous", "FULL")
                }
                val driver = JdbcSqliteDriver("jdbc:sqlite:${tempFile.absolutePath}", properties)
                try {
                    driver.execute(null, "PRAGMA foreign_keys = ON;", 0)
                    val triggerStream = classLoader.getResourceAsStream(
                        "schema-baselines/v1/canonical_triggers.sql"
                    ) ?: throw DatabaseValidationException("Historical Schema v1 trigger reference is missing")
                    val script = triggerStream.bufferedReader().use { it.readText() }
                    DatabaseDriverFactory().executeTriggerScript(driver, script)
                    val baseline = snapshotBaseline(driver)
                    if (baseline.fingerprint != V1_SCHEMA_FINGERPRINT) {
                        throw DatabaseValidationException(
                            "Historical Schema v1 reference fingerprint mismatch: ${baseline.fingerprint}"
                        )
                    }
                    return baseline
                } finally {
                    driver.close()
                }
            } finally {
                tempFile.delete()
                File("${tempFile.absolutePath}-wal").delete()
                File("${tempFile.absolutePath}-shm").delete()
            }
        }

        private fun snapshotBaseline(driver: SqlDriver): CanonicalSchemaBaseline {
            val tables = queryTableNames(driver)
            val tableDefs = queryTableDefinitions(driver)
            val tableColumns = tables.associateWith { queryTableColumns(driver, it) }
            val indexes = queryIndexes(driver)
            val triggers = queryTriggers(driver)
            val fingerprint = computeSchemaFingerprint(tables, tableDefs, driver, indexes, triggers)
            return CanonicalSchemaBaseline(
                tableNames = tables,
                tableDefinitions = tableDefs,
                tableColumns = tableColumns,
                indexes = indexes,
                triggers = triggers,
                fingerprint = fingerprint
            )
        }

        private fun buildV2CanonicalBaseline(): CanonicalSchemaBaseline {
            val classLoader = DatabaseDriverFactory::class.java.classLoader
            val snapshot = classLoader.getResourceAsStream("schema-baselines/v2/2.db")
                ?: throw DatabaseValidationException("Historical Schema v2 snapshot is missing")
            val tempFile = File.createTempFile("store-schema-v2-baseline-", ".db")
            try {
                snapshot.use { input -> Files.copy(input, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                val properties = Properties().apply {
                    put("foreign_keys", "true")
                    put("busy_timeout", "10000")
                    put("synchronous", "FULL")
                }
                val driver = JdbcSqliteDriver("jdbc:sqlite:${tempFile.absolutePath}", properties)
                try {
                    driver.execute(null, "PRAGMA foreign_keys = ON;", 0)
                    val triggerStream = classLoader.getResourceAsStream(
                        "schema-baselines/v2/canonical_triggers.sql"
                    ) ?: throw DatabaseValidationException("Historical Schema v2 trigger reference is missing")
                    val script = triggerStream.bufferedReader().use { it.readText() }
                    DatabaseDriverFactory().executeTriggerScript(driver, script)
                    val baseline = snapshotBaseline(driver)
                    if (baseline.fingerprint != V2_SCHEMA_FINGERPRINT) {
                        throw DatabaseValidationException(
                            "Schema v2 reference fingerprint mismatch: ${baseline.fingerprint}"
                        )
                    }
                    return baseline
                } finally {
                    driver.close()
                }
            } finally {
                tempFile.delete()
                File("${tempFile.absolutePath}-wal").delete()
                File("${tempFile.absolutePath}-shm").delete()
            }
        }

        fun getDefaultDatabaseFile(): File {
            val os = System.getProperty("os.name").lowercase()
            val baseDir = when {
                os.contains("win") -> {
                    val appData = System.getenv("LOCALAPPDATA")
                        ?: (System.getProperty("user.home") + "\\AppData\\Local")
                    File(appData, "StoreManagerPro\\data")
                }
                os.contains("mac") -> {
                    File(System.getProperty("user.home"), "Library/Application Support/StoreManagerPro/data")
                }
                else -> { // Linux / Unix
                    val xdgData = System.getenv("XDG_DATA_HOME")
                    if (!xdgData.isNullOrBlank()) {
                        File(xdgData, "storemanagerpro/data")
                    } else {
                        File(System.getProperty("user.home"), ".local/share/storemanagerpro/data")
                    }
                }
            }
            return File(baseDir, "store_manager_master.db")
        }

        fun createInMemoryDriver(): SqlDriver {
            val properties = Properties().apply {
                put("foreign_keys", "true")
                put("busy_timeout", "10000")
                put("synchronous", "FULL")
            }
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, properties)
            driver.execute(null, "PRAGMA foreign_keys = ON;", 0)
            driver.execute(null, "PRAGMA busy_timeout = 10000;", 0)
            driver.execute(null, "PRAGMA synchronous = FULL;", 0)

            StoreDatabase.Schema.create(driver)

            val factory = DatabaseDriverFactory()
            val script = factory.loadCanonicalTriggerScript()
            factory.executeTriggerScript(driver, script)
            driver.execute(null, "PRAGMA user_version = $EXPECTED_USER_VERSION;", 0)

            return driver
        }

        fun getSqliteVersion(driver: SqlDriver): String {
            val cursor = driver.executeQuery<String>(
                null,
                "SELECT sqlite_version();",
                {
                    it.next()
                    QueryResult.Value(it.getString(0) ?: "UNKNOWN")
                },
                0
            )
            return cursor.value
        }
    }
}
