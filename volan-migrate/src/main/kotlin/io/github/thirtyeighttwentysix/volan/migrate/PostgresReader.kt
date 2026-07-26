package io.github.thirtyeighttwentysix.volan.migrate

import io.github.thirtyeighttwentysix.volan.dialect.ColumnDefault
import io.github.thirtyeighttwentysix.volan.dialect.ColumnDefinition
import io.github.thirtyeighttwentysix.volan.dialect.ColumnType
import io.github.thirtyeighttwentysix.volan.dialect.ForeignKeyAction
import io.github.thirtyeighttwentysix.volan.dialect.ForeignKeyDefinition
import io.github.thirtyeighttwentysix.volan.dialect.IndexDefinition
import io.github.thirtyeighttwentysix.volan.dialect.PrimaryKeyDefinition
import io.github.thirtyeighttwentysix.volan.dialect.UniqueDefinition
import java.sql.Connection
import java.sql.ResultSet

/**
 * Reads a PostgreSQL database as the schema it holds.
 *
 * Everything is read from the catalogue rather than from `DatabaseMetaData`, because the catalogue is
 * the only place that says which unique index is a constraint, which order a composite key's columns
 * are in, and which values an enum type has — three things a migration cannot be written without.
 *
 * @property table the migration history table, which is Volan's own bookkeeping rather than part of
 *   the schema, and so is left out of what is read back.
 */
public class PostgresReader(
    private val types: NativeTypeTable = PostgresTypes,
    private val table: String = MigrationJournal.DEFAULT_TABLE,
) : DatabaseReader {
    override fun read(connection: Connection): DatabaseSchema {
        val enums = readEnums(connection)
        val columns = readColumns(connection, enums.map { it.name }.toSet())
        val constraints = readConstraints(connection)
        val foreignKeys = readForeignKeys(connection)
        val indexes = readIndexes(connection)
        val tables = columns.keys.sorted().map { name ->
            TableDefinition(
                name = name,
                columns = columns.getValue(name),
                primaryKey = constraints[name]?.primaryKey,
                uniques = constraints[name]?.uniques.orEmpty().sortedBy { it.name },
                indexes = indexes[name].orEmpty().sortedBy { it.name },
                foreignKeys = foreignKeys[name].orEmpty().sortedBy { it.name },
            )
        }
        return DatabaseSchema(enums.sortedBy { it.name }, tables)
    }

    private fun readEnums(connection: Connection): List<EnumDefinition> {
        val values = LinkedHashMap<String, MutableList<String>>()
        query(connection, ENUMS) { row ->
            values.getOrPut(row.getString("typname")) { ArrayList() }.add(row.getString("enumlabel"))
        }
        return values.map { (name, labels) -> EnumDefinition(name, labels) }
    }

    private fun readColumns(connection: Connection, enums: Set<String>): Map<String, List<ColumnDefinition>> {
        val columns = LinkedHashMap<String, MutableList<ColumnDefinition>>()
        query(connection, COLUMNS) { row ->
            val name = row.getString("table_name")
            if (name != table) columns.getOrPut(name) { ArrayList() }.add(column(row, enums))
        }
        return columns
    }

    private fun column(row: ResultSet, enums: Set<String>): ColumnDefinition {
        val udt = row.getString("udt_name")
        val default = row.getString("column_default")
        val type = when {
            row.getString("data_type") == "ARRAY" -> ColumnType.Array(readType(udt.removePrefix("_"), row))
            enums.contains(udt) -> ColumnType.Enumeration(udt)
            else -> readType(udt, row)
        }
        return ColumnDefinition(
            name = row.getString("column_name"),
            type = type,
            nullable = row.getString("is_nullable") == "YES",
            default = columnDefault(default, type),
            autoIncrement = default?.startsWith("nextval(") == true,
        )
    }

    private fun readType(udt: String, row: ResultSet): ColumnType = types.read(
        udtName = udt,
        length = row.getInt("character_maximum_length").takeUnless { row.wasNull() },
        precision = numericOrDatetimePrecision(row),
        scale = row.getInt("numeric_scale").takeUnless { row.wasNull() },
    )

    /** A numeric type is measured in digits and a time is measured in fractional seconds; only one applies. */
    private fun numericOrDatetimePrecision(row: ResultSet): Int? {
        val numeric = row.getInt("numeric_precision").takeUnless { row.wasNull() }
        if (numeric != null) return numeric
        return row.getInt("datetime_precision").takeUnless { row.wasNull() }
    }

    /**
     * Turns a default as PostgreSQL reports it back into what Volan calls it.
     *
     * The report is an expression with its type spelled out — `'USER'::user_role`, `'{}'::text[]` —
     * so the cast is taken off before the value is read. Anything that is not one of the shapes Volan
     * writes is kept as the expression it is, which is exactly what `dbgenerated` means.
     */
    private fun columnDefault(reported: String?, type: ColumnType): ColumnDefault? {
        if (reported == null || reported.startsWith("nextval(")) return null
        val expression = reported.substringBefore("::").trim()
        return when {
            expression.equals("CURRENT_TIMESTAMP", ignoreCase = true) || expression.equals("now()", ignoreCase = true) ->
                ColumnDefault.CurrentTimestamp
            expression.equals("gen_random_uuid()", ignoreCase = true) -> ColumnDefault.GeneratedUuid
            expression == "'{}'" && type is ColumnType.Array -> ColumnDefault.EmptyArray
            expression.equals("true", ignoreCase = true) -> ColumnDefault.Boolean(true)
            expression.equals("false", ignoreCase = true) -> ColumnDefault.Boolean(false)
            expression.startsWith("'") && expression.endsWith("'") ->
                ColumnDefault.Text(expression.trim('\'').replace("''", "'"))
            expression.toBigDecimalOrNull() != null -> ColumnDefault.Number(expression)
            else -> ColumnDefault.Expression(reported)
        }
    }

    private fun readConstraints(connection: Connection): Map<String, TableConstraints> {
        val keys = LinkedHashMap<String, LinkedHashMap<String, MutableList<String>>>()
        val kinds = LinkedHashMap<String, String>()
        query(connection, CONSTRAINTS) { row ->
            val name = row.getString("constraint_name")
            kinds[name] = row.getString("contype")
            keys.getOrPut(row.getString("table_name")) { LinkedHashMap() }
                .getOrPut(name) { ArrayList() }
                .add(row.getString("column_name"))
        }
        return keys.mapValues { (_, byName) ->
            val primary = byName.entries.firstOrNull { kinds[it.key] == "p" }
            TableConstraints(
                primaryKey = primary?.let { PrimaryKeyDefinition(it.key, it.value) },
                uniques = byName.entries.filter { kinds[it.key] == "u" }.map { UniqueDefinition(it.key, it.value) },
            )
        }
    }

    private fun readForeignKeys(connection: Connection): Map<String, List<ForeignKeyDefinition>> {
        val building = LinkedHashMap<String, LinkedHashMap<String, ForeignKeyBuilder>>()
        query(connection, FOREIGN_KEYS) { row ->
            val builder = building.getOrPut(row.getString("table_name")) { LinkedHashMap() }
                .getOrPut(row.getString("constraint_name")) {
                    ForeignKeyBuilder(
                        name = row.getString("constraint_name"),
                        targetTable = row.getString("target_table"),
                        onDelete = action(row.getString("confdeltype")),
                        onUpdate = action(row.getString("confupdtype")),
                    )
                }
            builder.columns.add(row.getString("column_name"))
            builder.targetColumns.add(row.getString("target_column"))
        }
        return building.mapValues { (_, byName) -> byName.values.map { it.build() } }
    }

    /** PostgreSQL records a referential action as one letter; these are the ones it uses. */
    private fun action(code: String): ForeignKeyAction = when (code) {
        "c" -> ForeignKeyAction.CASCADE
        "r" -> ForeignKeyAction.RESTRICT
        "n" -> ForeignKeyAction.SET_NULL
        "d" -> ForeignKeyAction.SET_DEFAULT
        else -> ForeignKeyAction.NO_ACTION
    }

    /**
     * Reads the indexes that are indexes in their own right.
     *
     * An index that backs a constraint is that constraint, and is read as one; reading it twice would
     * have a migration drop and recreate it forever.
     */
    private fun readIndexes(connection: Connection): Map<String, List<IndexDefinition>> {
        val indexes = LinkedHashMap<String, MutableList<IndexDefinition>>()
        query(connection, INDEXES) { row ->
            val name = row.getString("table_name")
            if (name != table) indexes.getOrPut(name) { ArrayList() }.add(index(row))
        }
        return indexes
    }

    private fun index(row: ResultSet): IndexDefinition {
        val name = row.getString("index_name")
        val definition = row.getString("definition")
        val columns = (row.getArray("columns").array as Array<*>).filterNotNull().map { it.toString() }
        if (columns.isNotEmpty()) {
            return IndexDefinition(name, columns, unique = row.getBoolean("is_unique"))
        }
        return IndexDefinition(name, fullTextColumns(name, definition), unique = false, fullText = true)
    }

    /**
     * The columns a full-text index covers, read back out of the expression it was built from.
     *
     * An index over an expression has no columns of its own to report, so the only place its columns
     * are written down is the expression. Volan recognises the one it writes; an expression index it
     * did not write is a question it has no honest answer to.
     */
    private fun fullTextColumns(name: String, definition: String): List<String> {
        if (!definition.contains("to_tsvector(")) {
            throw VolanMigrationException(
                "the index `$name` is built from an expression Volan did not write, so it cannot say what " +
                    "a schema would have to contain to produce it.\n" +
                    "  Volan can only manage a database it could also have created; leave that index to " +
                    "another tool, on a table Volan does not manage.",
            )
        }
        return COALESCED.findAll(definition).map { it.groupValues[1] }.toList()
    }

    private fun query(connection: Connection, sql: String, read: (ResultSet) -> Unit) {
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                while (result.next()) read(result)
            }
        }
    }

    private class TableConstraints(val primaryKey: PrimaryKeyDefinition?, val uniques: List<UniqueDefinition>)

    private class ForeignKeyBuilder(
        val name: String,
        val targetTable: String,
        val onDelete: ForeignKeyAction,
        val onUpdate: ForeignKeyAction,
    ) {
        val columns: MutableList<String> = ArrayList()
        val targetColumns: MutableList<String> = ArrayList()

        fun build(): ForeignKeyDefinition = ForeignKeyDefinition(name, columns, targetTable, targetColumns, onDelete, onUpdate)
    }

    private companion object {
        /**
         * The columns of a `to_tsvector` expression, as PostgreSQL writes it back.
         *
         * It does not hand back the text it was given: the function is uppercased, the literals carry
         * their types, and a name that needs no quoting loses its quotes. What survives all of that is
         * the first argument of each `COALESCE`, which is the column.
         */
        private val COALESCED = Regex("""COALESCE\("?(.+?)"?,""", RegexOption.IGNORE_CASE)

        private val ENUMS = """
            SELECT t.typname, e.enumlabel
            FROM pg_type t
            JOIN pg_enum e ON e.enumtypid = t.oid
            JOIN pg_namespace n ON n.oid = t.typnamespace
            WHERE n.nspname = current_schema()
            ORDER BY t.typname, e.enumsortorder
        """.trimIndent()

        private val COLUMNS = """
            SELECT c.table_name, c.column_name, c.is_nullable, c.column_default, c.data_type, c.udt_name,
                   c.character_maximum_length, c.numeric_precision, c.numeric_scale, c.datetime_precision
            FROM information_schema.columns c
            JOIN information_schema.tables t
              ON t.table_schema = c.table_schema AND t.table_name = c.table_name
            WHERE c.table_schema = current_schema() AND t.table_type = 'BASE TABLE'
            ORDER BY c.table_name, c.ordinal_position
        """.trimIndent()

        private val CONSTRAINTS = """
            SELECT con.conname AS constraint_name, con.contype, rel.relname AS table_name,
                   att.attname AS column_name
            FROM pg_constraint con
            JOIN pg_class rel ON rel.oid = con.conrelid
            JOIN pg_namespace n ON n.oid = rel.relnamespace
            CROSS JOIN LATERAL unnest(con.conkey) WITH ORDINALITY AS k(attnum, ord)
            JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = k.attnum
            WHERE con.contype IN ('p', 'u') AND n.nspname = current_schema()
            ORDER BY rel.relname, con.conname, k.ord
        """.trimIndent()

        private val FOREIGN_KEYS = """
            SELECT con.conname AS constraint_name, rel.relname AS table_name, tgt.relname AS target_table,
                   con.confdeltype, con.confupdtype,
                   src_att.attname AS column_name, tgt_att.attname AS target_column
            FROM pg_constraint con
            JOIN pg_class rel ON rel.oid = con.conrelid
            JOIN pg_class tgt ON tgt.oid = con.confrelid
            JOIN pg_namespace n ON n.oid = rel.relnamespace
            CROSS JOIN LATERAL unnest(con.conkey, con.confkey) WITH ORDINALITY AS k(src, dst, ord)
            JOIN pg_attribute src_att ON src_att.attrelid = con.conrelid AND src_att.attnum = k.src
            JOIN pg_attribute tgt_att ON tgt_att.attrelid = con.confrelid AND tgt_att.attnum = k.dst
            WHERE con.contype = 'f' AND n.nspname = current_schema()
            ORDER BY rel.relname, con.conname, k.ord
        """.trimIndent()

        private val INDEXES = """
            SELECT i.relname AS index_name, t.relname AS table_name, ix.indisunique AS is_unique,
                   pg_get_indexdef(i.oid) AS definition,
                   array_remove(array_agg(a.attname ORDER BY k.ord), NULL) AS columns
            FROM pg_index ix
            JOIN pg_class i ON i.oid = ix.indexrelid
            JOIN pg_class t ON t.oid = ix.indrelid
            JOIN pg_namespace n ON n.oid = t.relnamespace
            CROSS JOIN LATERAL unnest(string_to_array(ix.indkey::text, ' ')::int[]) WITH ORDINALITY AS k(attnum, ord)
            LEFT JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum
            WHERE n.nspname = current_schema()
              AND NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conindid = i.oid)
            GROUP BY i.relname, t.relname, ix.indisunique, i.oid
            ORDER BY t.relname, i.relname
        """.trimIndent()
    }
}
