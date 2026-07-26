package io.github.thirtyeighttwentysix.volan.ir

import io.github.thirtyeighttwentysix.volan.schema.SourceSpan
import io.github.thirtyeighttwentysix.volan.schema.Suggestions
import io.github.thirtyeighttwentysix.volan.schema.ast.Identifier

/**
 * Pairs up relation fields and checks that each pair describes a link a database can actually store.
 *
 * A relation is written on both models, and neither half means anything alone: which side holds the
 * foreign key, whether the link is one-to-one or one-to-many, and whether the referenced columns can
 * identify a row are all properties of the pair.
 */
@Suppress("TooManyFunctions")
internal class RelationAnalyzer(private val sink: DiagnosticSink, private val models: Map<String, ModelDraft>) {
    fun analyze(): List<Relation> {
        val ends = collectEnds()
        return ends.entries
            .sortedBy { it.key }
            .mapNotNull { (name, group) -> resolveGroup(name, group) }
    }

    /** Assigns every relation field its relation name and groups the fields that belong together. */
    private fun collectEnds(): Map<String, List<RelationEndDraft>> {
        val groups = LinkedHashMap<String, MutableList<RelationEndDraft>>()
        models.values.forEach { model ->
            model.relationFields.forEach { field ->
                field.relationName = field.explicitRelationName ?: derivedName(model.name, field.targetModel)
                groups.getOrPut(field.relationName) { ArrayList() }.add(RelationEndDraft(model, field))
            }
        }
        return groups
    }

    private fun derivedName(owner: String, target: String): String = listOf(owner, target).sorted().joinToString("To")

    private fun resolveGroup(name: String, group: List<RelationEndDraft>): Relation? {
        if (group.size == 1) {
            reportMissingOpposite(group.single())
            return null
        }
        if (group.size > 2) {
            reportAmbiguous(name, group)
            return null
        }
        val (first, second) = group
        if (first.field.targetModel != second.owner.name || second.field.targetModel != first.owner.name) {
            reportMismatchedPair(name, first, second)
            return null
        }
        return resolvePair(name, first, second)
    }

    private fun resolvePair(name: String, first: RelationEndDraft, second: RelationEndDraft): Relation? {
        val lists = listOf(first, second).filter { it.field.cardinality.isList }
        return when (lists.size) {
            2 -> resolveManyToMany(name, first, second)
            1 -> resolveOneToMany(name, singular = listOf(first, second).first { !it.field.cardinality.isList }, list = lists.single())
            else -> resolveOneToOne(name, first, second)
        }
    }

    private fun resolveManyToMany(name: String, first: RelationEndDraft, second: RelationEndDraft): Relation? {
        val declaring = listOf(first, second).filter { it.field.declaresForeignKey }
        if (declaring.isNotEmpty()) {
            declaring.forEach { end ->
                sink.error(
                    code = SemanticCode.RELATION_INVALID_SIDE,
                    span = end.field.fieldsArgumentSpan ?: end.field.span,
                    message = "a many-to-many relation has no foreign key to place",
                    label = "`fields` and `references` do not apply here",
                    help = "Volan keeps the pairs in a join table; to store extra columns on the link, " +
                        "declare a model for it and two one-to-many relations",
                )
            }
            return null
        }
        val ordered = listOf(first, second).sortedBy { it.owner.name }
        return Relation(
            name = name,
            kind = RelationKind.MANY_TO_MANY,
            from = ordered[0].toEnd(),
            to = ordered[1].toEnd(),
            foreignKeyFields = emptyList(),
            referencedFields = emptyList(),
            onDelete = null,
            onUpdate = null,
            joinTable = "_$name",
        )
    }

    private fun resolveOneToMany(name: String, singular: RelationEndDraft, list: RelationEndDraft): Relation? {
        if (list.field.declaresForeignKey) {
            sink.error(
                code = SemanticCode.RELATION_INVALID_SIDE,
                span = list.field.fieldsArgumentSpan ?: list.field.span,
                message = "the foreign key belongs on the side that holds one row",
                label = "`${list.owner.name}.${list.field.name}` holds many",
                help = "move `fields` and `references` to `${singular.owner.name}.${singular.field.name}`",
            )
            return null
        }
        if (!singular.field.declaresForeignKey) {
            reportMissingForeignKey(singular)
            return null
        }
        return buildRelation(name, RelationKind.ONE_TO_MANY, owner = singular, other = list)
    }

    private fun resolveOneToOne(name: String, first: RelationEndDraft, second: RelationEndDraft): Relation? {
        val declaring = listOf(first, second).filter { it.field.declaresForeignKey }
        if (declaring.size == 2) {
            sink.error(
                code = SemanticCode.RELATION_INVALID_SIDE,
                span = declaring[1].field.fieldsArgumentSpan ?: declaring[1].field.span,
                message = "only one side of a one-to-one relation holds the foreign key",
                label = "`${declaring[0].owner.name}.${declaring[0].field.name}` already holds it",
                help = "remove `fields` and `references` from one side",
            )
            return null
        }
        if (declaring.isEmpty()) {
            reportMissingForeignKey(first)
            return null
        }
        val owner = declaring.single()
        val other = listOf(first, second).first { it !== owner }
        val relation = buildRelation(name, RelationKind.ONE_TO_ONE, owner = owner, other = other) ?: return null
        validateForeignKeyIsUnique(owner)
        return relation
    }

    private fun buildRelation(name: String, kind: RelationKind, owner: RelationEndDraft, other: RelationEndDraft): Relation? {
        val target = models[owner.field.targetModel] ?: return null
        val foreignKeys = resolveFields(owner, owner.owner, owner.field.fields, owner.field.fieldsArgumentSpan, "fields")
        val references = resolveFields(owner, target, owner.field.references, owner.field.referencesArgumentSpan, "references")
        if (foreignKeys == null || references == null) return null
        if (!validateShape(owner, target, foreignKeys, references)) return null
        validateReferentialActions(owner, foreignKeys)
        validateSatisfiable(owner, kind)
        return Relation(
            name = name,
            kind = kind,
            from = owner.toEnd(),
            to = other.toEnd(),
            foreignKeyFields = foreignKeys.map { it.name },
            referencedFields = references.map { it.name },
            onDelete = owner.field.onDelete,
            onUpdate = owner.field.onUpdate,
            joinTable = null,
        )
    }

    /** Resolves the names in `fields:` or `references:` against the model they must belong to. */
    private fun resolveFields(
        end: RelationEndDraft,
        model: ModelDraft,
        names: List<Identifier>,
        argumentSpan: SourceSpan?,
        argument: String,
    ): List<ScalarField>? {
        if (names.isEmpty()) {
            sink.error(
                code = SemanticCode.MISSING_ATTRIBUTE_ARGUMENT,
                span = argumentSpan ?: end.field.span,
                message = "`@relation` is missing `$argument`",
                label = "a foreign key needs both `fields` and `references`",
                help = "write `@relation(fields: [${end.field.name}Id], references: [id])`",
            )
            return null
        }
        val resolved = names.mapNotNull { name ->
            model.field(name.text) ?: run {
                sink.error(
                    code = SemanticCode.UNKNOWN_FIELD_REFERENCE,
                    span = name.span,
                    message = "`${model.name}` has no field `${name.text}`",
                    label = "unknown field",
                    help = Suggestions.closest(name.text, model.fields.map { it.name })?.let { "did you mean `$it`?" }
                        ?: "`$argument` lists scalar fields of `${model.name}`",
                )
                null
            }
        }
        return resolved.takeIf { it.size == names.size }
    }

    private fun validateShape(
        end: RelationEndDraft,
        target: ModelDraft,
        foreignKeys: List<ScalarField>,
        references: List<ScalarField>,
    ): Boolean {
        if (foreignKeys.size != references.size) {
            sink.error(
                code = SemanticCode.RELATION_ARGUMENT_MISMATCH,
                span = end.field.fieldsArgumentSpan ?: end.field.span,
                message = "`fields` lists ${countOf(foreignKeys.size, "field")} but `references` lists ${references.size}",
                label = "the two lists pair up one to one",
            )
            return false
        }
        foreignKeys.zip(references).forEach { (key, reference) ->
            if (key.type != reference.type) {
                sink.error(
                    code = SemanticCode.RELATION_TYPE_MISMATCH,
                    span = end.field.fieldsArgumentSpan ?: end.field.span,
                    message = "`${end.owner.name}.${key.name}` and `${target.name}.${reference.name}` have different types",
                    label = "${typeName(key.type)} cannot reference ${typeName(reference.type)}",
                    help = "give the foreign key the same type as the field it points at",
                )
                return false
            }
        }
        if (references.map { it.name }.toSet() !in target.uniqueKeySets) {
            sink.error(
                code = SemanticCode.RELATION_REFERENCE_NOT_UNIQUE,
                span = end.field.referencesArgumentSpan ?: end.field.span,
                message = "`references` must point at fields that identify one row of `${target.name}`",
                label = "these fields are not unique",
                help = "point at the primary key, or add `@unique` to ${references.joinToString(", ") { "`${it.name}`" }}",
            )
            return false
        }
        return true
    }

    private fun validateForeignKeyIsUnique(owner: RelationEndDraft) {
        val keys = owner.field.fields.map { it.text }.toSet()
        if (keys in owner.owner.uniqueKeySets) return
        sink.error(
            code = SemanticCode.RELATION_FOREIGN_KEY_NOT_UNIQUE,
            span = owner.field.fieldsArgumentSpan ?: owner.field.span,
            message = "the foreign key of a one-to-one relation must be unique",
            label = "without it, two rows could point at the same row",
            help = "add `@unique` to ${keys.joinToString(", ") { "`$it`" }}, or make the other side a list",
        )
    }

    private fun validateReferentialActions(owner: RelationEndDraft, foreignKeys: List<ScalarField>) {
        if (owner.field.onDelete != ReferentialAction.SET_NULL) return
        val required = foreignKeys.filter { it.cardinality == Cardinality.REQUIRED }
        if (required.isEmpty()) return
        sink.error(
            code = SemanticCode.INVALID_REFERENTIAL_ACTION,
            span = owner.field.onDeleteSpan ?: owner.field.span,
            message = "`onDelete: SetNull` needs a foreign key that can be null",
            label = "${required.joinToString(", ") { "`${it.name}`" }} cannot hold null",
            help = "make ${required.joinToString(", ") { "`${it.name}`" }} optional with `?`, or use `Cascade` or `Restrict`",
        )
    }

    /** A model that requires a row of itself before its own first row can exist can never be written to. */
    private fun validateSatisfiable(owner: RelationEndDraft, kind: RelationKind) {
        if (kind == RelationKind.MANY_TO_MANY) return
        if (owner.owner.name != owner.field.targetModel) return
        if (owner.field.cardinality != Cardinality.REQUIRED) return
        sink.error(
            code = SemanticCode.UNSATISFIABLE_REQUIRED_RELATION,
            span = owner.field.nameSpan,
            message = "`${owner.owner.name}.${owner.field.name}` requires a `${owner.owner.name}` that must already exist",
            label = "the first row could never be written",
            help = "make it optional: `${owner.field.name} ${owner.field.targetModel}?`",
        )
    }

    private fun reportMissingForeignKey(end: RelationEndDraft) {
        sink.error(
            code = SemanticCode.RELATION_INVALID_SIDE,
            span = end.field.nameSpan,
            message = "neither side of this relation says where the foreign key lives",
            label = "one side must carry `fields` and `references`",
            help = "write `@relation(fields: [${end.field.name}Id], references: [id])` on the side that holds one row",
        )
    }

    private fun reportMissingOpposite(end: RelationEndDraft) {
        val target = end.field.targetModel
        val named = end.field.explicitRelationName?.let { " named \"$it\"" }.orEmpty()
        sink.error(
            code = SemanticCode.RELATION_MISSING_OPPOSITE,
            span = end.field.nameSpan,
            message = "`$target` has no field pointing back at `${end.owner.name}`",
            label = "a relation is declared on both models",
            help = "add a field$named to `$target`, for example `${end.owner.name.replaceFirstChar { it.lowercase() }}s " +
                "${end.owner.name}[]`",
        )
    }

    private fun reportAmbiguous(name: String, group: List<RelationEndDraft>) {
        group.forEach { end ->
            sink.error(
                code = SemanticCode.RELATION_AMBIGUOUS,
                span = end.field.nameSpan,
                message = "${group.size} fields claim the relation `$name`",
                label = "Volan cannot tell which two belong together",
                help = "give each relation its own name, for example `@relation(\"AuthoredPosts\", …)`",
            )
        }
    }

    private fun reportMismatchedPair(name: String, first: RelationEndDraft, second: RelationEndDraft) {
        sink.error(
            code = SemanticCode.RELATION_MISSING_OPPOSITE,
            span = second.field.nameSpan,
            message = "the two ends of relation `$name` do not point at each other",
            label = "`${second.owner.name}.${second.field.name}` points at `${second.field.targetModel}`",
            help = "`${first.owner.name}.${first.field.name}` points at `${first.field.targetModel}`; " +
                "both ends of a relation name the other's model",
        )
    }

    private fun typeName(type: FieldType): String = when (type) {
        is FieldType.Scalar -> "`${type.type.schemaName}`"
        is FieldType.EnumRef -> "`${type.enumName}`"
    }

    /** One end of a relation while it is being resolved: the model it is on, and the field itself. */
    private class RelationEndDraft(val owner: ModelDraft, val field: RelationFieldDraft) {
        fun toEnd(): RelationEnd = RelationEnd(owner.name, field.name, field.cardinality)
    }
}
