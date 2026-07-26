package io.github.thirtyeighttwentysix.volan.runtime

/**
 * What the runtime knows about one column.
 *
 * Generated code builds these once, as constants. Nothing here is discovered by reflection.
 *
 * @property field the field name in the schema.
 * @property column the column name in the database.
 * @property isNullable whether the column accepts null.
 * @property isGenerated whether the database produces the value, so writes may leave it out.
 * @property isUpdatedAt whether Volan overwrites it on every update.
 */
public data class ColumnMetadata(
    public val field: String,
    public val column: String,
    public val isNullable: Boolean,
    public val isGenerated: Boolean,
    public val isUpdatedAt: Boolean,
)

/**
 * What the runtime knows about one relation.
 *
 * @property field the relation field on this model.
 * @property target the model at the other end.
 * @property isList whether this side holds many rows.
 * @property ownsForeignKey whether the foreign key lives on this model rather than on the target.
 * @property foreignKeyColumns the columns holding the key, on whichever side owns it.
 * @property referencedColumns the columns they point at.
 * @property joinTable the table holding the pairs of a many-to-many relation, `null` otherwise.
 */
public data class RelationMetadata(
    public val field: String,
    public val target: String,
    public val isList: Boolean,
    public val ownsForeignKey: Boolean,
    public val foreignKeyColumns: List<String>,
    public val referencedColumns: List<String>,
    public val joinTable: String?,
)

/**
 * What the runtime knows about one model.
 *
 * @property model the model name in the schema.
 * @property table the table name in the database.
 * @property columns every column, in the order the model declares them.
 * @property primaryKey the columns making up the primary key.
 * @property relations every relation the model takes part in.
 */
public data class TableMetadata(
    public val model: String,
    public val table: String,
    public val columns: List<ColumnMetadata>,
    public val primaryKey: List<String>,
    public val relations: List<RelationMetadata>,
) {
    private val columnsByField: Map<String, ColumnMetadata> = columns.associateBy { it.field }

    /** Returns the column backing the field called [field], or `null` if the model has no such field. */
    public fun column(field: String): ColumnMetadata? = columnsByField[field]

    /** Returns the relation called [field], or `null` if the model has no such relation. */
    public fun relation(field: String): RelationMetadata? = relations.firstOrNull { it.field == field }
}
