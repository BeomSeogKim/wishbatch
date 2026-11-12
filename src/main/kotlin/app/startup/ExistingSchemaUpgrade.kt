package app.startup

import java.sql.Connection
import java.sql.Types
import javax.sql.DataSource

internal class ExistingSchemaUpgrade(private val dataSource: DataSource) {
    private val oldStates = setOf(
        "ABANDONED", "AUTHORIZED", "CAPTURED", "CAPTURE_FAILED", "CAPTURE_PENDING", "CREATED",
        "REFUNDED", "REFUND_FAILED", "REFUND_PENDING", "VOIDED", "VOID_FAILED",
    )
    private val states = oldStates + "PARTIALLY_REFUNDED"

    fun upgrade() {
        dataSource.connection.use { connection ->
            val postgres = when (connection.metaData.databaseProductName) {
                "PostgreSQL" -> true
                "H2" -> false
                else -> error("Schema upgrade supports only PostgreSQL and H2")
            }
            // PostgreSQL must commit a new enum label before Hibernate can use it.
            connection.autoCommit = true
            upgradePledgeState(connection, postgres)
            upgradeDuplicateCount(connection)
            upgradePledgeWish(connection)
        }
    }

    private fun upgradePledgeWish(connection: Connection) {
        val table = identifier(connection, "pledges")
        if (!tableExists(connection, table)) return
        val column = identifier(connection, "wish_id")
        if (columnInfo(connection, table, column) == null) {
            execute(connection, "alter table ${qualified(connection, table)} add column ${quote(column)} uuid")
        }
    }

    private fun upgradePledgeState(connection: Connection, postgres: Boolean) {
        val table = identifier(connection, "pledges")
        if (!tableExists(connection, table)) return
        val column = identifier(connection, "state")
        val info = columnInfo(connection, table, column)
            ?: error("Unsupported schema: pledges.state is missing")
        val checks = stateChecks(connection, table, column, postgres)
        checks.forEach { (_, expression) -> validateCheck(expression) }
        val qualified = qualified(connection, table)
        val stateColumn = quote(column)
        val values = connection.createStatement().use { statement ->
            statement.executeQuery("select distinct $stateColumn from $qualified").use { rows ->
                buildSet { while (rows.next()) add(rows.getString(1)) }
            }
        }
        check(values.all { it in states }) { "Unsupported schema: pledges.state contains unknown or null values" }

        if (!postgres) {
            val nativeEnum = info.typeName.startsWith("ENUM(")
            val labels = if (nativeEnum) queryStrings(connection, """
                select e.value_name from information_schema.enum_values e
                join information_schema.columns c on c.table_catalog = e.object_catalog
                    and c.table_schema = e.object_schema and c.table_name = e.object_name
                    and c.dtd_identifier = e.enum_identifier
                where c.table_schema = ? and c.table_name = ? and c.column_name = ?
                order by e.value_ordinal
            """.trimIndent(), connection.schema, table, column) else {
                validateStringColumn(info)
                oldStates.toList()
            }
            validateLabels(labels)
            // Native ENUM avoids H2 CHECK expressions retaining a closed initialization session.
            checks.forEach { (name, _) -> execute(connection, "alter table $qualified drop constraint ${quote(name)}") }
            if (!nativeEnum || "PARTIALLY_REFUNDED" !in labels) {
                execute(connection, "alter table $qualified alter column $stateColumn enum(${sqlLabels((labels + "PARTIALLY_REFUNDED").distinct())})")
            }
        } else if (info.typeName !in setOf("varchar", "text")) {
            val type = connection.prepareStatement("""
                select tn.nspname, t.typname from pg_attribute a
                join pg_class c on c.oid = a.attrelid join pg_namespace n on n.oid = c.relnamespace
                join pg_type t on t.oid = a.atttypid join pg_namespace tn on tn.oid = t.typnamespace
                where n.nspname = ? and c.relname = ? and a.attname = ? and t.typtype = 'e'
            """.trimIndent()).use { statement ->
                statement.setString(1, connection.schema)
                statement.setString(2, table)
                statement.setString(3, column)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "Unsupported schema: pledges.state is not a native enum" }
                    rows.getString(1) to rows.getString(2)
                }
            }
            val labels = queryStrings(connection, """
                select e.enumlabel from pg_enum e join pg_type t on t.oid = e.enumtypid
                join pg_namespace n on n.oid = t.typnamespace
                where n.nspname = ? and t.typname = ? order by e.enumsortorder
            """.trimIndent(), type.first, type.second)
            validateLabels(labels)
            if ("PARTIALLY_REFUNDED" !in labels) {
                execute(connection, "alter type ${quote(type.first)}.${quote(type.second)} add value 'PARTIALLY_REFUNDED'")
            }
            widenPostgresChecks(connection, qualified, stateColumn, checks, addIfMissing = false)
        } else {
            validateStringColumn(info)
            widenPostgresChecks(connection, qualified, stateColumn, checks, addIfMissing = true)
        }
    }

    private fun widenPostgresChecks(connection: Connection, table: String, column: String,
        checks: List<Pair<String, String>>, addIfMissing: Boolean) {
        if ((addIfMissing && checks.isEmpty()) || checks.any { (_, expression) -> "PARTIALLY_REFUNDED" !in expression }) {
            val add = "add constraint ${quote("pledges_state_values")} check ($column in (${sqlLabels(states)}))"
            val actions = checks.map { (name, _) -> "drop constraint ${quote(name)}" } + add
            execute(connection, "alter table $table ${actions.joinToString(", ")}")
        }
    }

    private fun validateStringColumn(info: ColumnInfo) {
        check(info.jdbcType == Types.VARCHAR && info.size >= 18) {
            "Unsupported schema: pledges.state must be a string of at least 18 characters or a native enum"
        }
    }

    private fun upgradeDuplicateCount(connection: Connection) {
        val table = identifier(connection, "wishes")
        if (!tableExists(connection, table)) return
        val column = identifier(connection, "duplicate_count")
        val info = columnInfo(connection, table, column)
        val qualified = qualified(connection, table)
        val field = quote(column)
        if (info == null) {
            execute(connection, "alter table $qualified add column $field integer default 0 not null")
        } else {
            check(info.jdbcType == Types.INTEGER) { "Unsupported schema: wishes.duplicate_count must be INTEGER" }
            connection.createStatement().use { statement ->
                statement.executeQuery("select count(*) from $qualified where $field < 0").use { rows ->
                    rows.next()
                    check(rows.getLong(1) == 0L) { "Unsupported schema: wishes.duplicate_count contains negative values" }
                }
            }
            if (info.nullable) {
                execute(connection, "update $qualified set $field = 0 where $field is null")
                execute(connection, "alter table $qualified alter column $field set not null")
            }
            execute(connection, "alter table $qualified alter column $field set default 0")
        }
    }

    private fun stateChecks(connection: Connection, table: String, column: String, postgres: Boolean): List<Pair<String, String>> {
        val sql = if (postgres) """
            select k.conname, pg_get_constraintdef(k.oid) from pg_constraint k
            join pg_class t on t.oid = k.conrelid join pg_namespace n on n.oid = t.relnamespace
            join pg_attribute a on a.attrelid = t.oid and a.attnum = any(k.conkey)
            where k.contype = 'c' and n.nspname = ? and t.relname = ? and a.attname = ?
        """ else """
            select c.constraint_name, c.check_clause from information_schema.check_constraints c
            join information_schema.constraint_column_usage u
                on u.constraint_catalog = c.constraint_catalog and u.constraint_schema = c.constraint_schema
                and u.constraint_name = c.constraint_name
            where u.table_schema = ? and u.table_name = ? and u.column_name = ?
        """
        return connection.prepareStatement(sql.trimIndent()).use { statement ->
            statement.setString(1, connection.schema)
            statement.setString(2, table)
            statement.setString(3, column)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.getString(1) to rows.getString(2)) }
            }
        }
    }

    private fun validateCheck(expression: String) {
        val literal = Regex("'([A-Z_]+)'")
        val labels = literal.findAll(expression).map { it.groupValues[1] }.toList()
        val structure = expression.replace(literal, "?")
            .replace(Regex("::(?:character varying|text)(?:\\[\\])?", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[\\s\"()]"), "").uppercase()
        val placeholders = labels.joinToString(",") { "?" }
        check(structure == "STATEIN$placeholders" || structure == "CHECKSTATE=ANYARRAY[$placeholders]") {
            "Unsupported schema: pledges.state has a non-enum CHECK constraint"
        }
        validateLabels(labels)
    }

    private fun validateLabels(labels: List<String>) {
        check(labels.size == labels.toSet().size && (labels.toSet() == oldStates || labels.toSet() == states)) {
            "Unsupported schema: pledges.state has an unexpected enum label set"
        }
    }

    private data class ColumnInfo(val jdbcType: Int, val typeName: String, val size: Int, val nullable: Boolean)

    private fun columnInfo(connection: Connection, table: String, column: String): ColumnInfo? =
        connection.metaData.getColumns(connection.catalog, connection.schema, table, column).use { rows ->
            if (!rows.next()) null else ColumnInfo(rows.getInt("DATA_TYPE"), rows.getString("TYPE_NAME"),
                rows.getInt("COLUMN_SIZE"), rows.getInt("NULLABLE") != 0)
        }

    private fun tableExists(connection: Connection, table: String) =
        connection.metaData.getTables(connection.catalog, connection.schema, table, arrayOf("TABLE")).use { it.next() }

    private fun queryStrings(connection: Connection, sql: String, vararg parameters: String): List<String> =
        connection.prepareStatement(sql).use { statement ->
            parameters.forEachIndexed { index, value -> statement.setString(index + 1, value) }
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }

    private fun identifier(connection: Connection, value: String) =
        if (connection.metaData.storesUpperCaseIdentifiers()) value.uppercase() else value

    private fun qualified(connection: Connection, table: String) = "${quote(connection.schema)}.${quote(table)}"
    private fun quote(value: String) = "\"${value.replace("\"", "\"\"")}\""
    private fun sqlLabels(labels: Collection<String>) = labels.joinToString(",") { "'$it'" }
    private fun execute(connection: Connection, sql: String) { connection.createStatement().use { it.execute(sql) } }
}
