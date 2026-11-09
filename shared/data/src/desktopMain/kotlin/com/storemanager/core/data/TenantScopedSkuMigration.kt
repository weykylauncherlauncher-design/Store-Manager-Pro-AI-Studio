package com.storemanager.core.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import java.util.Locale

/** Rebuilds the product-variant dependency closure while foreign keys stay enabled. */
internal class TenantScopedSkuMigration(
    private val driver: SqlDriver,
    private val canonicalTriggerScript: String,
    private val failurePoint: String? = null
) {
    private data class SqlObject(val name: String, val table: String, val sql: String)
    private data class Variant(val id: String, val productId: String, val sku: String, val tenantId: String)

    fun migrate() {
        check(queryLong("PRAGMA foreign_keys") == 1L) { "Foreign keys must remain enabled" }
        val variants = loadVariantsAndValidate()
        driver.execute(null, "CREATE UNIQUE INDEX ux_products_id_tenant ON products(id, tenant_id)", 0)
        val sourceTables = tableDefinitions()
        val rebuilt = findDependentTables(setOf("product_variants"))
        val order = parentFirstOrder(rebuilt)

        for (name in order) {
            var sql = sourceTables[name] ?: error("Missing table SQL for $name")
            if (name == "product_variants") {
                sql = sql.replace("sku TEXT NOT NULL UNIQUE", "sku TEXT NOT NULL")
                val close = sql.lastIndexOf(')')
                sql = sql.substring(0, close).trimEnd() + ",\n    tenant_id TEXT NOT NULL,\n    UNIQUE(tenant_id, sku),\n" +
                    "CHECK(sku = trim(sku) AND sku = upper(sku)), " +
                    "\n    FOREIGN KEY(product_id, tenant_id) REFERENCES products(id, tenant_id) ON DELETE RESTRICT\n)"
            }
            driver.execute(null, rewriteCreate(sql, name, rebuilt), 0)
        }
        failIfRequested("after_new_tables")

        for (name in order) {
            if (name == "product_variants") {
                for (variant in variants) {
                    driver.execute(
                        null,
                        "INSERT INTO product_variants__v3(id, product_id, sku, barcode, variant_name, attributes_json, is_active, tenant_id) " +
                            "SELECT id, product_id, ?, barcode, variant_name, attributes_json, is_active, ? FROM product_variants WHERE id = ?",
                        3
                    ) {
                        bindString(0, variant.sku)
                        bindString(1, variant.tenantId)
                        bindString(2, variant.id)
                    }
                }
            } else {
                val columns = tableColumns(name)
                val joined = columns.joinToString(",")
                driver.execute(null, "INSERT INTO ${name}__v3($joined) SELECT $joined FROM $name", 0)
            }
        }
        failIfRequested("after_copy")

        val indexes = queryIndexes()
        val triggers = queryTriggers()
        triggers.forEach { driver.execute(null, "DROP TRIGGER \"${it.name}\"", 0) }
        indexes.filter { it.table in rebuilt }.forEach { driver.execute(null, "DROP INDEX \"${it.name}\"", 0) }

        val remaining = rebuilt.toMutableSet()
        while (remaining.isNotEmpty()) {
            val leaf = remaining.firstOrNull { candidate ->
                remaining.none { child -> foreignKeyParents(child).contains(candidate) }
            } ?: error("Cyclic dependency while dropping rebuilt tables")
            driver.execute(null, "DROP TABLE \"$leaf\"", 0)
            remaining.remove(leaf)
        }
        failIfRequested("after_drop_old")

        order.forEach { name -> driver.execute(null, "ALTER TABLE ${name}__v3 RENAME TO $name", 0) }
        indexes.filter { it.table in rebuilt }.forEach { driver.execute(null, it.sql, 0) }
        executeTriggerScript(canonicalTriggerScript)

        check(queryLong("SELECT COUNT(*) FROM pragma_foreign_key_check") == 0L) {
            "v2 to v3 migration produced foreign-key violations"
        }
        check(queryString("PRAGMA integrity_check") == "ok") { "v2 to v3 migration failed integrity_check" }
        failIfRequested("before_commit")
    }

    private fun loadVariantsAndValidate(): List<Variant> {
        val rows = queryRows(
            "SELECT v.id, v.product_id, v.sku, p.tenant_id, p.id FROM product_variants v " +
                "LEFT JOIN products p ON p.id = v.product_id ORDER BY v.id"
        , 5)
        val seen = mutableSetOf<String>()
        return rows.map { row ->
            check(row[4].isNotEmpty()) { "Orphan product variant: ${row[0]}" }
            val tenant = row[3]
            val normalized = row[2].trim().uppercase(Locale.ROOT)
            check(normalized.isNotEmpty()) { "Empty normalized SKU for variant ${row[0]}" }
            check(seen.add("$tenant\u0000$normalized")) {
                "Normalized SKU conflict for tenant $tenant: $normalized"
            }
            Variant(row[0], row[1], normalized, tenant)
        }
    }

    private fun findDependentTables(roots: Set<String>): Set<String> {
        val result = roots.toMutableSet()
        var changed = true
        while (changed) {
            changed = false
            queryRows("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", 1).forEach { row ->
                if (row[0] !in result && foreignKeyParents(row[0]).any { it in result }) {
                    result += row[0]
                    changed = true
                }
            }
        }
        return result
    }

    private fun parentFirstOrder(names: Set<String>): List<String> {
        val result = mutableListOf<String>()
        val pending = names.toMutableSet()
        while (pending.isNotEmpty()) {
            val ready = pending.filter { foreignKeyParents(it).none { parent -> parent in pending } }.sorted()
            check(ready.isNotEmpty()) { "Cyclic dependency in rebuilt tables" }
            result += ready
            pending.removeAll(ready.toSet())
        }
        return result
    }

    private fun rewriteCreate(sql: String, name: String, rebuilt: Set<String>): String {
        val tablePattern = Regex("(?i)(CREATE TABLE\\s+(?:IF NOT EXISTS\\s+)?)${Regex.escape(name)}\\b")
        var result = tablePattern.replaceFirst(sql, "${'$'}1${name}__v3")
        rebuilt.forEach { dependency ->
            result = Regex("(?i)(REFERENCES\\s+)${Regex.escape(dependency)}\\b")
                .replace(result, "${'$'}1${dependency}__v3")
        }
        return result
    }

    private fun tableDefinitions(): Map<String, String> = queryRows(
        "SELECT name, sql FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
        , 2).associate { it[0] to it[1] }

    private fun tableColumns(name: String): List<String> = queryRows("PRAGMA table_info(\"$name\")", 6).map { it[1] }

    private fun foreignKeyParents(name: String): Set<String> =
        queryRows("PRAGMA foreign_key_list(\"$name\")", 8).map { it[2] }.toSet()

    private fun queryIndexes(): List<SqlObject> = queryRows(
        "SELECT name, tbl_name, sql FROM sqlite_master WHERE type='index' AND sql IS NOT NULL"
        , 3).map { SqlObject(it[0], it[1], it[2]) }

    private fun queryTriggers(): List<SqlObject> = queryRows(
        "SELECT name, tbl_name, sql FROM sqlite_master WHERE type='trigger'"
        , 3).map { SqlObject(it[0], it[1], it[2]) }

    private fun executeTriggerScript(script: String) {
        script.split("CREATE TRIGGER").drop(1).forEach { part ->
            val trimmed = part.trim()
            if (trimmed.isNotEmpty()) driver.execute(null, "CREATE TRIGGER $trimmed", 0)
        }
    }

    private fun failIfRequested(point: String) {
        if (failurePoint == point) error("Intentional v2 to v3 failure at $point")
    }

    private fun queryLong(sql: String): Long = driver.executeQuery<Long>(null, sql, {
        it.next()
        QueryResult.Value(it.getLong(0) ?: 0L)
    }, 0).value

    private fun queryString(sql: String): String? = driver.executeQuery<String?>(null, sql, {
        it.next()
        QueryResult.Value(it.getString(0))
    }, 0).value

    private fun queryRows(sql: String, columnCount: Int): List<List<String>> = driver.executeQuery<List<List<String>>>(null, sql, {
        val result = mutableListOf<List<String>>()
        while (it.next().value) {
            val row = mutableListOf<String>()
            for (index in 0 until columnCount) row += it.getString(index) ?: ""
            result += row
        }
        QueryResult.Value(result)
    }, 0).value
}
