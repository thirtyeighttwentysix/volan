package io.github.thirtyeighttwentysix.volan.ir

import io.github.thirtyeighttwentysix.volan.schema.SourceSpan

/** Rejects storage shapes SQLite cannot represent before any client source is generated. */
internal class SqliteSchemaAnalyzer(private val sink: DiagnosticSink) {
    fun analyze(models: List<ModelDraft>) {
        models.forEach { model ->
            model.fields.forEach { field ->
                if (field.type == FieldType.Scalar(ScalarType.DECIMAL)) {
                    reject(
                        field.span,
                        "SQLite cannot preserve Decimal precision and numeric query semantics together",
                        "use Long with a fixed scale for exact amounts, or Double for approximate values",
                    )
                }
                if (field.cardinality == Cardinality.LIST) {
                    reject(
                        field.span,
                        "`${model.name}.${field.name}` is a scalar list, which SQLite cannot store",
                        "use a related model for list elements, or an explicit Json field",
                    )
                }
                if (field.nativeType != null) {
                    reject(
                        field.span,
                        "SQLite does not support Volan's `@db.${field.nativeType.name}` native type override",
                        "remove the @db attribute; SQLite storage is chosen from the field's scalar type",
                    )
                }
                if (field.default == DefaultValue.AutoIncrement && model.primaryKey?.fields != listOf(field.name)) {
                    reject(
                        field.span,
                        "SQLite autoincrement requires a single-column integer primary key",
                        "put @id on this field and remove the composite primary key, or supply the value yourself",
                    )
                }
            }
            model.indexes.filter { it.kind == IndexKind.FULLTEXT }.forEach {
                reject(
                    model.span,
                    "SQLite's FTS virtual tables cannot be expressed with @@fulltext",
                    "use an ordinary @@index, or manage the FTS table with raw SQL",
                )
            }
        }
    }

    private fun reject(span: SourceSpan, message: String, help: String) {
        sink.error(SemanticCode.UNSUPPORTED_PROVIDER_FEATURE, span, message, "unsupported SQLite storage", help)
    }
}
