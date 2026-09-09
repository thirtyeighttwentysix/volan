package io.github.thirtyeighttwentysix.volan.dialect

import org.jspecify.annotations.NullMarked
import org.jspecify.annotations.Nullable

/**
 * The column types Volan describes a schema in.
 *
 * These are SQL's vocabulary, not the schema language's: what a `DateTime` field becomes is decided
 * on the way into this model, and what a [TIMESTAMP] is called is decided on the way out of it by the
 * dialect. Neither end has to know the other's names.
 */
@NullMarked
public enum class SqlType {
    /** Text of no fixed length. */
    TEXT,

    /** A 32-bit integer. */
    INTEGER,

    /** A 64-bit integer. */
    BIGINT,

    /** A 32-bit floating point number. */
    REAL,

    /** A 64-bit floating point number. */
    DOUBLE,

    /** An exact decimal. */
    NUMERIC,

    /** A truth value. */
    BOOLEAN,

    /** A moment in time. */
    TIMESTAMP,

    /** A date with no time of day. */
    DATE,

    /** A time of day with no date. */
    TIME,

    /** A JSON document. */
    JSON,

    /** Arbitrary bytes. */
    BLOB,

    /** A UUID. */
    UUID,
}

/** What a column holds. */
@NullMarked
public sealed interface ColumnType {
    /** One of Volan's own types, named by the dialect. */
    @NullMarked
    public data class Scalar(public val type: SqlType) : ColumnType

    /** Exactly the type the schema asked for with `@db.…`, passed through untranslated. */
    @NullMarked
    public data class Native @JvmOverloads constructor(public val name: String, public val arguments: List<String> = emptyList()) :
        ColumnType

    /** A type declared by an `enum` block. */
    @NullMarked
    public data class Enumeration(public val name: String) : ColumnType

    /** A list of values of [element]. */
    @NullMarked
    public data class Array(public val element: ColumnType) : ColumnType
}

/**
 * What a column holds when a write supplies nothing.
 *
 * A literal is rendered into the DDL text rather than bound, because a default belongs to the table
 * rather than to any one statement — there is nowhere to bind it to. Every literal here comes from the
 * schema file, never from a caller's value, which is what keeps that safe.
 */
@NullMarked
public sealed interface ColumnDefault {
    /** A text literal. */
    @NullMarked
    public data class Text(public val value: String) : ColumnDefault

    /** A numeric literal, kept as text so that no precision is lost on the way through. */
    @NullMarked
    public data class Number(public val value: String) : ColumnDefault

    /** A truth literal. */
    @NullMarked
    public data class Boolean(public val value: kotlin.Boolean) : ColumnDefault

    /** The empty list, the only default a list column may have. */
    @NullMarked
    public data object EmptyArray : ColumnDefault

    /** The moment the row is written, named by the dialect. */
    @NullMarked
    public data object CurrentTimestamp : ColumnDefault

    /** A UUID the database generates, named by the dialect. */
    @NullMarked
    public data object GeneratedUuid : ColumnDefault

    /** An expression written in the schema, passed through as it stands. */
    @NullMarked
    public data class Expression(public val sql: String) : ColumnDefault
}

/**
 * One column of a table.
 *
 * @property name the column name.
 * @property type what it holds.
 * @property nullable whether it may hold null.
 * @property default what it holds when a write supplies nothing.
 * @property autoIncrement whether the database assigns an increasing value.
 */
@NullMarked
public data class ColumnDefinition @JvmOverloads constructor(
    public val name: String,
    public val type: ColumnType,
    public val nullable: Boolean,
    public val default: @Nullable ColumnDefault? = null,
    public val autoIncrement: Boolean = false,
)

/** A primary key over one or more columns. */
@NullMarked
public data class PrimaryKeyDefinition(public val name: @Nullable String?, public val columns: List<String>)

/** A unique constraint over one or more columns. */
@NullMarked
public data class UniqueDefinition(public val name: String, public val columns: List<String>)

/** What to do to the dependent rows when the row they point at is deleted or its key changes. */
@NullMarked
public enum class ForeignKeyAction(public val sql: String) {
    /** Do the same to them. */
    CASCADE("CASCADE"),

    /** Refuse while they exist. */
    RESTRICT("RESTRICT"),

    /** Leave it to the database's own behaviour. */
    NO_ACTION("NO ACTION"),

    /** Set the foreign key to null. */
    SET_NULL("SET NULL"),

    /** Set the foreign key back to its default. */
    SET_DEFAULT("SET DEFAULT"),
}

/**
 * A foreign key.
 *
 * @property name the constraint name.
 * @property columns the columns on this table, in order.
 * @property targetTable the table pointed at.
 * @property targetColumns the columns pointed at, matching [columns] in order.
 */
@NullMarked
public data class ForeignKeyDefinition @JvmOverloads constructor(
    public val name: String,
    public val columns: List<String>,
    public val targetTable: String,
    public val targetColumns: List<String>,
    public val onDelete: ForeignKeyAction = ForeignKeyAction.NO_ACTION,
    public val onUpdate: ForeignKeyAction = ForeignKeyAction.NO_ACTION,
)

/** An index over one or more columns. */
@NullMarked
public data class IndexDefinition @JvmOverloads constructor(
    public val name: String,
    public val columns: List<String>,
    public val unique: Boolean = false,
    public val fullText: Boolean = false,
)

/** One thing to change about a column that already exists. */
@NullMarked
public sealed interface ColumnChange {
    /** Give it a different type. */
    @NullMarked
    public data class Type @JvmOverloads constructor(public val type: ColumnType, public val using: @Nullable String? = null) : ColumnChange

    /** Let it hold null, or stop letting it. */
    @NullMarked
    public data class Nullability(public val nullable: kotlin.Boolean) : ColumnChange

    /** Give it a default, or take its default away. */
    @NullMarked
    public data class Default(public val default: @Nullable ColumnDefault?) : ColumnChange
}

/**
 * One statement of a migration, described rather than written.
 *
 * A dialect turns each of these into the text its database understands — one statement in most cases,
 * several where a database has no direct way to say it. Nothing above this layer writes DDL.
 */
@NullMarked
public sealed interface DdlStatement {
    /** Creates a table with its columns and primary key; constraints and indexes follow separately. */
    @NullMarked
    public data class CreateTable @JvmOverloads constructor(
        public val table: String,
        public val columns: List<ColumnDefinition>,
        public val primaryKey: @Nullable PrimaryKeyDefinition? = null,
    ) : DdlStatement

    /** Drops a table and everything that belongs to it. */
    @NullMarked
    public data class DropTable(public val table: String) : DdlStatement

    /** Adds a column to a table that already exists. */
    @NullMarked
    public data class AddColumn(public val table: String, public val column: ColumnDefinition) : DdlStatement

    /** Drops a column. */
    @NullMarked
    public data class DropColumn(public val table: String, public val column: String) : DdlStatement

    /** Changes one thing about a column. */
    @NullMarked
    public data class AlterColumn(
        public val table: String,
        public val column: String,
        public val change: ColumnChange,
    ) : DdlStatement

    /** Adds a primary key to a table that had none. */
    @NullMarked
    public data class AddPrimaryKey(public val table: String, public val key: PrimaryKeyDefinition) : DdlStatement

    /** Adds a unique constraint. */
    @NullMarked
    public data class AddUnique(public val table: String, public val constraint: UniqueDefinition) : DdlStatement

    /** Adds a foreign key. */
    @NullMarked
    public data class AddForeignKey(public val table: String, public val key: ForeignKeyDefinition) : DdlStatement

    /** Drops a named constraint: a primary key, a unique constraint or a foreign key. */
    @NullMarked
    public data class DropConstraint(public val table: String, public val name: String) : DdlStatement

    /** Creates an index. */
    @NullMarked
    public data class CreateIndex(public val table: String, public val index: IndexDefinition) : DdlStatement

    /** Drops an index. */
    @NullMarked
    public data class DropIndex(public val table: String, public val name: String) : DdlStatement

    /** Creates an enum type. */
    @NullMarked
    public data class CreateEnum(public val name: String, public val values: List<String>) : DdlStatement

    /** Drops an enum type. */
    @NullMarked
    public data class DropEnum(public val name: String) : DdlStatement

    /** Adds a value to an enum type that already exists. */
    @NullMarked
    public data class AddEnumValue @JvmOverloads constructor(
        public val name: String,
        public val value: String,
        public val after: @Nullable String? = null,
    ) : DdlStatement
}
