package io.github.thirtyeighttwentysix.volan.ir

/** Checks the H2 storage subset supported by the generated client. */
internal class H2SchemaAnalyzer(private val sink: DiagnosticSink) {
    fun analyze(models: List<ModelDraft>) {
        models.forEach { model ->
            model.fields.filter { it.nativeType != null }.forEach { field ->
                sink.error(
                    SemanticCode.UNSUPPORTED_PROVIDER_FEATURE,
                    field.span,
                    "H2 does not yet support Volan's @db native type overrides",
                    "unsupported H2 storage",
                    "remove the @db attribute; H2 storage is chosen from the field's scalar type",
                )
            }
            model.indexes.filter { it.kind == IndexKind.FULLTEXT }.forEach {
                sink.error(
                    SemanticCode.UNSUPPORTED_PROVIDER_FEATURE,
                    model.span,
                    "H2 full-text indexes cannot be expressed with @@fulltext",
                    "unsupported H2 storage",
                    "use an ordinary @@index, or manage the full-text tables with raw SQL",
                )
            }
        }
    }
}
