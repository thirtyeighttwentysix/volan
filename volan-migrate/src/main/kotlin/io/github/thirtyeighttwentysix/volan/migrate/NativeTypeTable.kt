package io.github.thirtyeighttwentysix.volan.migrate

import io.github.thirtyeighttwentysix.volan.dialect.ColumnType
import io.github.thirtyeighttwentysix.volan.dialect.SqlType
import io.github.thirtyeighttwentysix.volan.ir.NativeType
import io.github.thirtyeighttwentysix.volan.ir.Provider
import io.github.thirtyeighttwentysix.volan.ir.ScalarType

/**
 * What one database's column types are called, in both directions.
 *
 * A `@db.…` type and the type a driver reports are two spellings of the same thing, and a diff has to
 * see them as one. Both are brought to the same form here: `@db.Text` on a `String` and a plain
 * `String` describe the same column, so they become the same value, and the differ has nothing to
 * report between a schema and the database it was applied to.
 */
public interface NativeTypeTable {
    /** The type `@db.…` names on a field declared as [scalar]. */
    public fun canonical(scalar: ScalarType, native: NativeType): ColumnType

    /**
     * The type a driver's report names.
     *
     * @param udtName the underlying type name, as `information_schema` gives it.
     * @param length the declared character length, when there is one.
     * @param precision the declared numeric or fractional-second precision, when there is one.
     * @param scale the declared numeric scale, when there is one.
     */
    public fun read(udtName: String, length: Int?, precision: Int?, scale: Int?): ColumnType

    public companion object {
        /**
         * The table for [provider].
         *
         * @throws VolanMigrationException for a provider whose dialect has not landed yet, because a
         *   table of guesses would be worse than none.
         */
        @JvmStatic
        public fun forProvider(provider: Provider): NativeTypeTable = when (provider) {
            Provider.POSTGRESQL -> PostgresTypes
            else -> throw VolanMigrationException(
                "Volan cannot yet map the column types of ${provider.id}, so it cannot migrate one.\n" +
                    "  The other dialects, and the types that come with them, arrive in M8.",
            )
        }
    }
}

/**
 * PostgreSQL's column types.
 *
 * The names on the left of each pair are the ones a schema writes after `@db.`; the ones on the right
 * are what PostgreSQL calls them. A `@db.…` that names exactly what the field's own type would have
 * produced is dropped, because the two describe one column and carrying both spellings would make a
 * schema differ from the database it created.
 */
public object PostgresTypes : NativeTypeTable {
    override fun canonical(scalar: ScalarType, native: NativeType): ColumnType {
        val arguments = native.arguments
        val plain = plainEquivalent(native.name, arguments)
        if (plain != null) return ColumnType.Scalar(plain)
        val known = KNOWN.firstOrNull { it.equals(native.name, ignoreCase = true) } ?: throw unknown(native)
        return ColumnType.Native(known, arguments)
    }

    /** The Volan type a `@db.…` is merely another name for, or `null` when it says something more. */
    private fun plainEquivalent(name: String, arguments: List<String>): SqlType? = when {
        name.equals("Text", ignoreCase = true) -> SqlType.TEXT
        name.equals("Integer", ignoreCase = true) -> SqlType.INTEGER
        name.equals("BigInt", ignoreCase = true) -> SqlType.BIGINT
        name.equals("Real", ignoreCase = true) -> SqlType.REAL
        name.equals("DoublePrecision", ignoreCase = true) -> SqlType.DOUBLE
        name.equals("Boolean", ignoreCase = true) -> SqlType.BOOLEAN
        name.equals("Date", ignoreCase = true) -> SqlType.DATE
        name.equals("JsonB", ignoreCase = true) -> SqlType.JSON
        name.equals("ByteA", ignoreCase = true) -> SqlType.BLOB
        name.equals("Uuid", ignoreCase = true) -> SqlType.UUID
        name.equals("Decimal", ignoreCase = true) && arguments == DECIMAL_DEFAULT -> SqlType.NUMERIC
        name.equals("Timestamp", ignoreCase = true) && arguments == MILLISECONDS -> SqlType.TIMESTAMP
        name.equals("Time", ignoreCase = true) && arguments == MILLISECONDS -> SqlType.TIME
        else -> null
    }

    override fun read(udtName: String, length: Int?, precision: Int?, scale: Int?): ColumnType = when (udtName) {
        "text" -> ColumnType.Scalar(SqlType.TEXT)
        "int4" -> ColumnType.Scalar(SqlType.INTEGER)
        "int8" -> ColumnType.Scalar(SqlType.BIGINT)
        "float4" -> ColumnType.Scalar(SqlType.REAL)
        "float8" -> ColumnType.Scalar(SqlType.DOUBLE)
        "bool" -> ColumnType.Scalar(SqlType.BOOLEAN)
        "date" -> ColumnType.Scalar(SqlType.DATE)
        "jsonb" -> ColumnType.Scalar(SqlType.JSON)
        "bytea" -> ColumnType.Scalar(SqlType.BLOB)
        "uuid" -> ColumnType.Scalar(SqlType.UUID)
        "int2" -> ColumnType.Native("SmallInt")
        "json" -> ColumnType.Native("Json")
        "money" -> ColumnType.Native("Money")
        "inet" -> ColumnType.Native("Inet")
        "xml" -> ColumnType.Native("Xml")
        "citext" -> ColumnType.Native("Citext")
        "oid" -> ColumnType.Native("Oid")
        "varchar" -> sized("VarChar", length)
        "bpchar" -> sized("Char", length)
        "bit" -> sized("Bit", length)
        "varbit" -> sized("VarBit", length)
        "numeric" -> decimal(precision, scale)
        "timestamp" -> precise("Timestamp", SqlType.TIMESTAMP, precision)
        "timestamptz" -> ColumnType.Native("Timestamptz", listOfNotNull(precision?.toString()))
        "time" -> precise("Time", SqlType.TIME, precision)
        "timetz" -> ColumnType.Native("Timetz", listOfNotNull(precision?.toString()))
        else -> throw VolanMigrationException(
            "this database has a column of type `$udtName`, which Volan has no name for.\n" +
                "  Volan can only migrate a database whose types it can also write; leave that table to " +
                "another tool, or say what the column should be with `@db.…`.",
        )
    }

    private fun sized(name: String, length: Int?): ColumnType = ColumnType.Native(name, listOfNotNull(length?.toString()))

    private fun decimal(precision: Int?, scale: Int?): ColumnType {
        val arguments = listOfNotNull(precision?.toString(), scale?.toString())
        return if (arguments == DECIMAL_DEFAULT) ColumnType.Scalar(SqlType.NUMERIC) else ColumnType.Native("Decimal", arguments)
    }

    private fun precise(name: String, plain: SqlType, precision: Int?): ColumnType =
        if (precision == MILLISECOND_DIGITS) ColumnType.Scalar(plain) else ColumnType.Native(name, listOfNotNull(precision?.toString()))

    private fun unknown(native: NativeType): VolanMigrationException {
        val suggestion = KNOWN.firstOrNull { it.startsWith(native.name.take(2), ignoreCase = true) }
        return VolanMigrationException(
            "PostgreSQL has no type `@db.${native.name}`." +
                (suggestion?.let { "\n  Did you mean `@db.$it`?" } ?: "") +
                "\n  The types it does have are: ${KNOWN.joinToString(", ")}.",
        )
    }

    private const val MILLISECOND_DIGITS = 3
    private val MILLISECONDS = listOf(MILLISECOND_DIGITS.toString())
    private val DECIMAL_DEFAULT = listOf("65", "30")

    /** Every type a schema may name after `@db.` for PostgreSQL, in the spelling Volan writes back. */
    private val KNOWN = listOf(
        "BigInt", "Bit", "Boolean", "ByteA", "Char", "Citext", "Date", "Decimal", "DoublePrecision",
        "Inet", "Integer", "Json", "JsonB", "Money", "Oid", "Real", "SmallInt", "Text", "Time",
        "Timestamp", "Timestamptz", "Timetz", "Uuid", "VarBit", "VarChar", "Xml",
    )
}
