package io.github.thirtyeighttwentysix.volan.dialect.postgres

import io.github.thirtyeighttwentysix.volan.dialect.ColumnType
import io.github.thirtyeighttwentysix.volan.dialect.DdlRenderer
import io.github.thirtyeighttwentysix.volan.dialect.DialectCapabilities
import io.github.thirtyeighttwentysix.volan.dialect.IndexDefinition
import io.github.thirtyeighttwentysix.volan.dialect.SqlRenderer
import io.github.thirtyeighttwentysix.volan.dialect.SqlType

/**
 * PostgreSQL.
 *
 * Almost everything comes from [SqlRenderer]: PostgreSQL is the database Volan's SQL model was shaped
 * around, so the dialect mostly states what it can do and lets the standard rendering stand.
 */
public object PostgresDialect : DdlRenderer(
    DialectCapabilities(
        returningClause = true,
        insertOnConflict = true,
        caseInsensitiveLike = true,
        distinctOn = true,
        nullsOrdering = true,
        tupleComparison = true,
        arrayColumns = true,
        // The wire protocol counts parameters in a 16-bit field. Volan chunks well below this, but the
        // planner needs the real ceiling to know where a batch has to be split.
        maximumParameters = 65_535,
    ),
) {
    override val id: String get() = "postgresql"

    override val hasEnumTypes: Boolean get() = true

    override val generatedUuid: String get() = "gen_random_uuid()"

    override fun typeName(type: SqlType): String = when (type) {
        SqlType.TEXT -> "text"
        SqlType.INTEGER -> "integer"
        SqlType.BIGINT -> "bigint"
        SqlType.REAL -> "real"
        SqlType.DOUBLE -> "double precision"
        SqlType.NUMERIC -> "numeric(65, 30)"
        SqlType.BOOLEAN -> "boolean"
        SqlType.TIMESTAMP -> "timestamp(3)"
        SqlType.DATE -> "date"
        SqlType.TIME -> "time(3)"
        SqlType.JSON -> "jsonb"
        SqlType.BLOB -> "bytea"
        SqlType.UUID -> "uuid"
    }

    /**
     * PostgreSQL spells an auto-incrementing column as a type of its own rather than as a modifier.
     *
     * `serial` and `bigserial` are what the introspection of an existing database reports back as a
     * default of `nextval(…)`, so this and the reader agree without either naming the other.
     */

    /**
     * A full-text index is an index over a document, not over the columns it is made of.
     *
     * PostgreSQL has no `FULLTEXT` keyword: what it has is a GIN index over `to_tsvector`, which is
     * the same thing said differently. The configuration is a literal and every column is coalesced,
     * because the expression has to be immutable and total for an index to be built from it.
     */
    override fun createIndex(table: String, index: IndexDefinition): String {
        if (!index.fullText) return super.createIndex(table, index)
        val document = index.columns.joinToString(" || ' ' || ") { "coalesce(${quote(it)}, '')" }
        return "CREATE INDEX ${quote(index.name)} ON ${quote(table)} USING GIN (to_tsvector('simple', $document))"
    }

    override fun autoIncrementType(type: ColumnType): String? = when {
        type == ColumnType.Scalar(SqlType.INTEGER) -> "serial"
        type == ColumnType.Scalar(SqlType.BIGINT) -> "bigserial"
        else -> null
    }
}
