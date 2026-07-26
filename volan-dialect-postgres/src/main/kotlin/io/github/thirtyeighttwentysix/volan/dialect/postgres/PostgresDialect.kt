package io.github.thirtyeighttwentysix.volan.dialect.postgres

import io.github.thirtyeighttwentysix.volan.dialect.DialectCapabilities
import io.github.thirtyeighttwentysix.volan.dialect.SqlRenderer

/**
 * PostgreSQL.
 *
 * Almost everything comes from [SqlRenderer]: PostgreSQL is the database Volan's SQL model was shaped
 * around, so the dialect mostly states what it can do and lets the standard rendering stand.
 */
public object PostgresDialect : SqlRenderer(
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
}
