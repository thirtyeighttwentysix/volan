package io.github.thirtyeighttwentysix.volan.migrate

import io.github.thirtyeighttwentysix.volan.dialect.ColumnType
import io.github.thirtyeighttwentysix.volan.dialect.SqlType
import io.github.thirtyeighttwentysix.volan.ir.NativeType
import io.github.thirtyeighttwentysix.volan.ir.ScalarType
import java.util.Locale

/** H2 scalar declarations used for DDL and introspection; native overrides are not yet supported. */
public object H2Types : NativeTypeTable {
    override fun canonical(scalar: ScalarType, native: NativeType): ColumnType =
        throw VolanMigrationException("H2 does not yet support @db type overrides.")

    override fun read(udtName: String, length: Int?, precision: Int?, scale: Int?): ColumnType {
        val type = when (udtName.uppercase(Locale.ROOT)) {
            "CHARACTER VARYING" -> SqlType.TEXT
            "INTEGER" -> SqlType.INTEGER
            "BIGINT" -> SqlType.BIGINT
            "REAL" -> SqlType.REAL
            "DOUBLE PRECISION" -> SqlType.DOUBLE
            "NUMERIC" -> SqlType.NUMERIC
            "BOOLEAN" -> SqlType.BOOLEAN
            "TIMESTAMP WITH TIME ZONE" -> SqlType.TIMESTAMP
            "DATE" -> SqlType.DATE
            "TIME" -> SqlType.TIME
            "JSON" -> SqlType.JSON
            "UUID" -> SqlType.UUID
            "BINARY VARYING" -> SqlType.BLOB
            else -> throw VolanMigrationException("Unsupported H2 column type: $udtName")
        }
        val plain = when (type) {
            SqlType.TEXT, SqlType.BLOB -> length == null || length == MAX_LENGTH
            SqlType.NUMERIC -> (precision == null || precision == NUMERIC_PRECISION) && (scale == null || scale == NUMERIC_SCALE)
            SqlType.TIMESTAMP, SqlType.TIME -> precision == null || precision == TIME_PRECISION
            else -> true
        }
        if (!plain) throw VolanMigrationException("H2 cannot map the non-default length or precision of $udtName yet.")
        return ColumnType.Scalar(type)
    }

    private const val MAX_LENGTH = 1_000_000_000
    private const val NUMERIC_PRECISION = 65
    private const val NUMERIC_SCALE = 30
    private const val TIME_PRECISION = 9
}
