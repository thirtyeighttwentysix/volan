package io.github.thirtyeighttwentysix.volan.ir

/** Rejects unsupported storage before generation, instead of producing an unusable client. */
internal class MySqlSchemaAnalyzer(private val sink: DiagnosticSink) {
    fun analyze(models: List<ModelDraft>) {
        models.forEach { model ->
            model.fields.forEach { field ->
                val reason = when {
                    field.cardinality == Cardinality.LIST -> "scalar arrays require a related model or Json"
                    field.nativeType != null -> "@db native overrides are not yet supported"
                    field.default == DefaultValue.AutoIncrement && model.primaryKey?.fields != listOf(field.name) ->
                        "autoincrement requires a single-column integer primary key"
                    field.default == DefaultValue.Uuid && model.primaryKey?.fields?.contains(field.name) == true ->
                        "a UUID primary key must be supplied by the application for JDBC read-back"
                    else -> null
                }
                if (reason != null) {
                    sink.error(
                        SemanticCode.UNSUPPORTED_PROVIDER_FEATURE,
                        field.span,
                        "MySQL/MariaDB: $reason",
                        "unsupported storage",
                        "change the field storage before generating the client",
                    )
                }
            }
            model.relationFields.filter { it.onDelete == ReferentialAction.SET_DEFAULT || it.onUpdate == ReferentialAction.SET_DEFAULT }
                .forEach {
                    sink.error(
                        SemanticCode.UNSUPPORTED_PROVIDER_FEATURE,
                        it.span,
                        "MySQL/MariaDB does not support SetDefault",
                        "unsupported action",
                        "use Restrict, Cascade or SetNull",
                    )
                }
        }
    }
}
