package io.github.thirtyeighttwentysix.volan.runtime

/**
 * What the runtime knows about the models of one schema.
 *
 * Generated code builds this once, from constants. It is the only place the runtime learns that
 * `User` is stored in `users`, which is what lets everything above it speak in schema names while
 * everything below it speaks in database names.
 */
public class TableRegistry(tables: List<TableMetadata>) {
    private val byModel: Map<String, TableMetadata> = tables.associateBy { it.model }

    /** Every model in the schema, in the order it was registered. */
    public val models: List<TableMetadata> = tables.toList()

    /** Returns what is known about [model], or `null` when the schema has no such model. */
    public fun find(model: String): TableMetadata? = byModel[model]

    /**
     * Returns what is known about [model].
     *
     * @throws VolanConfigurationException when the schema has no such model, which means the client
     *   and the registry it was given came from different schemas.
     */
    public fun require(model: String): TableMetadata = byModel[model] ?: throw VolanConfigurationException(
        "this client knows nothing about a model called `$model`. " +
            "It was built for ${byModel.keys.sorted().joinToString(", ")}, which suggests the generated client " +
            "and the schema it is running against have drifted apart.",
    )

    /**
     * Returns the column backing [field] on [model].
     *
     * @throws VolanConfigurationException when the model has no such field.
     */
    public fun requireColumn(model: String, field: String): ColumnMetadata {
        val table = require(model)
        return table.column(field) ?: throw VolanConfigurationException(
            "`$model` has no field called `$field`. It has ${table.columns.joinToString(", ") { it.field }}.",
        )
    }

    /**
     * Returns the relation called [field] on [model].
     *
     * @throws VolanConfigurationException when the model has no such relation.
     */
    public fun requireRelation(model: String, field: String): RelationMetadata {
        val table = require(model)
        return table.relation(field) ?: throw VolanConfigurationException(
            "`$model` has no relation called `$field`. " +
                "It has ${table.relations.joinToString(", ") { it.field }.ifEmpty { "no relations at all" }}.",
        )
    }
}
