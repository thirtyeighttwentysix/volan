package io.github.thirtyeighttwentysix.volan.migrate

import io.github.thirtyeighttwentysix.volan.dialect.DdlRenderer
import io.github.thirtyeighttwentysix.volan.dialect.DdlStatement

/**
 * One change a migration makes.
 *
 * @property statement what to do, described rather than written.
 * @property warning what will be lost by doing it, when something will be. A step with no warning is
 *   safe to apply to a database with rows in it.
 */
public data class MigrationStep(public val statement: DdlStatement, public val warning: String? = null)

/**
 * The changes that take a database from one state to another, in an order that keeps it valid at every
 * point along the way.
 *
 * A plan holds no SQL. Rendering it is a separate step, so the same plan can be shown to a person,
 * compared in a test, and applied to a database — and so the SQL a migration file holds is the SQL that
 * was reviewed.
 */
public data class MigrationPlan(public val steps: List<MigrationStep>) {
    /** Whether the database already matches the schema. */
    public val isEmpty: Boolean get() = steps.isEmpty()

    /** Everything this plan will lose, in the order it will be lost. */
    public val warnings: List<String> get() = steps.mapNotNull { it.warning }

    /** Whether applying this plan loses anything. */
    public val isDestructive: Boolean get() = steps.any { it.warning != null }

    /** The statements this plan becomes on [dialect], one string each. */
    public fun render(dialect: DdlRenderer): List<String> = steps.flatMap { step ->
        dialect.render(step.statement).map { it.sql }
    }

    /** The plan as the contents of a migration file: one statement per paragraph, each ended with `;`. */
    public fun toSql(dialect: DdlRenderer): String = render(dialect).joinToString(";\n\n", postfix = ";\n")

    public companion object {
        /** A plan that changes nothing. */
        @JvmField
        public val EMPTY: MigrationPlan = MigrationPlan(emptyList())
    }
}
