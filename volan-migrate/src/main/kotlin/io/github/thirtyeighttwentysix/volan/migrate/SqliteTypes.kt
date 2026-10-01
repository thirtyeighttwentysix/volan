package io.github.thirtyeighttwentysix.volan.migrate

import io.github.thirtyeighttwentysix.volan.dialect.ColumnType
import io.github.thirtyeighttwentysix.volan.dialect.SqlType
import io.github.thirtyeighttwentysix.volan.ir.NativeType
import io.github.thirtyeighttwentysix.volan.ir.ScalarType
import java.util.Locale

/** Declared types preserve Volan scalar distinctions while selecting the required SQLite affinity. */
public object SqliteTypes : NativeTypeTable {
    override fun canonical(scalar: ScalarType, native: NativeType): ColumnType =
        throw VolanMigrationException("SQLite does not support @db type overrides.")

    override fun read(udtName: String, length: Int?, precision: Int?, scale: Int?): ColumnType {
        val type = when (udtName.trim().uppercase(Locale.ROOT)) {
            "TEXT" -> SqlType.TEXT
            "INTEGER", "INT" -> SqlType.INTEGER
            "BIGINT" -> SqlType.BIGINT
            "FLOAT" -> SqlType.REAL
            "DOUBLE", "REAL" -> SqlType.DOUBLE
            "BOOLEAN" -> SqlType.BOOLEAN
            "TIMESTAMP TEXT" -> SqlType.TIMESTAMP
            "DATE TEXT" -> SqlType.DATE
            "TIME TEXT" -> SqlType.TIME
            "JSON TEXT" -> SqlType.JSON
            "UUID TEXT" -> SqlType.UUID
            "BLOB" -> SqlType.BLOB
            else -> SqliteSql.unsupported("column type $udtName")
        }
        return ColumnType.Scalar(type)
    }
}
