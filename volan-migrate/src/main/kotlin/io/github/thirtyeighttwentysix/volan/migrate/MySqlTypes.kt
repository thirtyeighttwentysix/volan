package io.github.thirtyeighttwentysix.volan.migrate

import io.github.thirtyeighttwentysix.volan.dialect.ColumnType
import io.github.thirtyeighttwentysix.volan.dialect.SqlType
import io.github.thirtyeighttwentysix.volan.ir.NativeType
import io.github.thirtyeighttwentysix.volan.ir.ScalarType

/** Canonical storage shared by MySQL and MariaDB; nonstandard sizes are refused. */
public object MySqlTypes : NativeTypeTable {
    override fun canonical(scalar: ScalarType, native: NativeType): ColumnType =
        throw VolanMigrationException("MySQL and MariaDB @db overrides are not yet supported.")

    // These sizes identify the canonical DDL declarations, rather than configurable limits.
    @Suppress("MagicNumber")
    override fun read(udtName: String, length: Int?, precision: Int?, scale: Int?): ColumnType {
        val type = when (udtName.lowercase()) {
            "varchar" -> if (length == 191) SqlType.TEXT else unsupported(udtName)
            "char" -> if (length == 36) SqlType.UUID else unsupported(udtName)
            "int", "integer" -> SqlType.INTEGER
            "bigint" -> SqlType.BIGINT
            "float" -> SqlType.REAL
            "double" -> SqlType.DOUBLE
            "decimal" -> if (precision == 65 && scale == 30) SqlType.NUMERIC else unsupported(udtName)
            "tinyint" -> SqlType.BOOLEAN
            "datetime" -> if (precision == 6) SqlType.TIMESTAMP else unsupported(udtName)
            "date" -> SqlType.DATE
            "time" -> if (precision == 6) SqlType.TIME else unsupported(udtName)
            "json" -> SqlType.JSON
            "longblob" -> SqlType.BLOB
            else -> unsupported(udtName)
        }
        return ColumnType.Scalar(type)
    }

    private fun unsupported(name: String): Nothing = throw VolanMigrationException("Unsupported MySQL/MariaDB storage declaration: $name.")
}
