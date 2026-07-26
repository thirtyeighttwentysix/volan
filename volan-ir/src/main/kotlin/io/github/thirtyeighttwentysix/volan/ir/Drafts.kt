package io.github.thirtyeighttwentysix.volan.ir

import io.github.thirtyeighttwentysix.volan.schema.SourceSpan
import io.github.thirtyeighttwentysix.volan.schema.ast.Identifier

/**
 * A relation field before its other end has been found.
 *
 * Relations can only be validated once every model has been read, so the model pass records what the
 * user wrote and the relation pass decides what it means.
 *
 * @property explicitRelationName the name given in `@relation("…")`, if any.
 * @property fieldsArgumentSpan where `fields:` was written, so its diagnostics point at it.
 */
internal class RelationFieldDraft(
    val name: String,
    val nameSpan: SourceSpan,
    val targetModel: String,
    val cardinality: Cardinality,
    val explicitRelationName: String?,
    val fields: List<Identifier>,
    val references: List<Identifier>,
    val onDelete: ReferentialAction?,
    val onUpdate: ReferentialAction?,
    val onDeleteSpan: SourceSpan?,
    val isIgnored: Boolean,
    val documentation: String?,
    val span: SourceSpan,
    val fieldsArgumentSpan: SourceSpan?,
    val referencesArgumentSpan: SourceSpan?,
) {
    /** The relation this field belongs to, filled in once both ends are known. */
    var relationName: String = ""

    /** True when this side declares the foreign key. */
    val declaresForeignKey: Boolean
        get() = fields.isNotEmpty() || references.isNotEmpty()

    fun toRelationField(): RelationField = RelationField(
        name = name,
        targetModel = targetModel,
        cardinality = cardinality,
        relationName = relationName,
        fields = fields.map { it.text },
        references = references.map { it.text },
        onDelete = onDelete,
        onUpdate = onUpdate,
        isIgnored = isIgnored,
        documentation = documentation,
        span = span,
    )
}

/** A model after its own contents have been checked, but before relations have been paired up. */
internal class ModelDraft(
    val name: String,
    val nameSpan: SourceSpan,
    val dbName: String,
    val fields: List<ScalarField>,
    val relationFields: List<RelationFieldDraft>,
    val primaryKey: PrimaryKey?,
    val uniques: List<UniqueConstraint>,
    val indexes: List<Index>,
    val isIgnored: Boolean,
    val documentation: String?,
    val span: SourceSpan,
) {
    private val fieldsByName: Map<String, ScalarField> = fields.associateBy { it.name }

    fun field(name: String): ScalarField? = fieldsByName[name]

    /**
     * Every set of fields that identifies at most one row: the primary key, each `@unique` field and
     * each `@@unique` constraint. A relation may only point at one of these.
     */
    val uniqueKeySets: List<Set<String>>
        get() = buildList {
            primaryKey?.let { add(it.fields.toSet()) }
            fields.filter { it.isUnique }.forEach { add(setOf(it.name)) }
            uniques.forEach { add(it.fields.toSet()) }
        }

    fun toModel(): Model = Model(
        name = name,
        dbName = dbName,
        fields = fields,
        relationFields = relationFields.map { it.toRelationField() },
        primaryKey = primaryKey,
        uniques = uniques,
        indexes = indexes,
        isIgnored = isIgnored,
        documentation = documentation,
        span = span,
    )
}
