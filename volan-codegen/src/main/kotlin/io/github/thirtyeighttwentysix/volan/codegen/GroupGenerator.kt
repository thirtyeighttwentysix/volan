package io.github.thirtyeighttwentysix.volan.codegen

import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import io.github.thirtyeighttwentysix.volan.ir.Model

/**
 * Generates the way a model is folded into groups: which fields define a group, what to work out about
 * each one, which groups survive, and how a group reads back.
 *
 * A group carries values only for the fields it was grouped by, which is the same shape a partial
 * `select` produces — so it reads through the same projection, and reading a field the grouping left
 * out fails the same way, naming the block that would add it.
 */
internal class GroupGenerator(private val types: TypeResolver, private val aggregates: AggregateGenerator) {
    fun having(model: Model): TypeSpec = TypeSpec.classBuilder("${model.name}Having")
        .addKdoc(
            "Which groups of `${model.name}` survive, written over what was worked out about them.\n\n" +
                "Only summaries are on offer here. A condition on a grouped field is the same condition on " +
                "the rows that went into the group, which is what `where` is for — and `where` narrows " +
                "before the grouping work is done rather than after it.\n",
        )
        .superclass(Types.havingScope)
        .addProperties(aggregates.havingProperties(model))
        .build()

    fun scope(model: Model): TypeSpec = TypeSpec.classBuilder("${model.name}GroupScope")
        .addKdoc("How to fold `${model.name}` into groups, and what to work out about each one.\n")
        .superclass(Types.groupScope)
        .addSuperclassConstructorParameter("%S", model.name)
        .addFunction(
            FunSpec.builder("by")
                .addKdoc("The fields whose values define a group.\n")
                .addParameter("block", lambdaOn(types.declared("${model.name}Fields")))
                .addStatement("recordGrouping(%T().apply(block).build())", types.declared("${model.name}Fields"))
                .build(),
        )
        .addFunction(
            FunSpec.builder("where")
                .addKdoc("Which rows go into the groups at all.\n")
                .addParameter("block", lambdaOn(types.declared("${model.name}Where")))
                .addStatement("recordFilter(%T().apply(block))", types.declared("${model.name}Where"))
                .build(),
        )
        .addFunction(
            FunSpec.builder("having")
                .addKdoc("Which groups survive, judged on what was worked out about them.\n")
                .addParameter("block", lambdaOn(types.declared("${model.name}Having")))
                .addStatement("recordHaving(%T().apply(block))", types.declared("${model.name}Having"))
                .build(),
        )
        .addFunction(
            FunSpec.builder("orderBy")
                .addKdoc("How to sort the groups, by the fields they are grouped on.\n")
                .addParameter("block", lambdaOn(types.declared("${model.name}OrderBy")))
                .addStatement("recordOrder(%T().apply(block))", types.declared("${model.name}OrderBy"))
                .build(),
        )
        .addFunctions(aggregates.summaryFunctions(model))
        .build()

    fun result(model: Model): TypeSpec {
        val projection = types.declared("${model.name}Projection")
        val builder = TypeSpec.classBuilder("${model.name}Group")
            .addKdoc(
                "One group of `${model.name}`: the values that define it, and what was worked out about it.\n\n" +
                    "A field the `by` block left out refuses to be read, because the group has no single " +
                    "value for it.\n",
            )
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("key", projection)
                    .addParameter("values", Types.valueMap)
                    .build(),
            )
            .addProperty(PropertySpec.builder("key", projection).addModifiers(KModifier.PRIVATE).initializer("key").build())
            .addProperty(
                PropertySpec.builder("values", Types.valueMap).addModifiers(KModifier.PRIVATE).initializer("values").build(),
            )
        model.fields.forEach { field ->
            val name = field.name.replaceFirstChar { it.uppercase() }
            builder.addProperty(
                PropertySpec.builder(field.name, types.fieldType(field.type, field.cardinality))
                    .addKdoc(
                        "The value of `${field.name}` this group is for.\n\n" +
                            "@throws io.github.thirtyeighttwentysix.volan.runtime.VolanFieldNotSelectedException " +
                            "if the query did not group by it.\n",
                    )
                    .getter(FunSpec.getterBuilder().addStatement("return key.%L", field.name).build())
                    .build(),
            )
            builder.addProperty(
                PropertySpec.builder("is${name}Grouped", BOOLEAN)
                    .addKdoc("Whether the query grouped by `${field.name}`.\n")
                    .getter(FunSpec.getterBuilder().addStatement("return key.is%LSelected", name).build())
                    .build(),
            )
        }
        return builder.addProperties(aggregates.summaryProperties(model, "are in this group")).build()
    }

    private fun lambdaOn(receiver: TypeName): TypeName = LambdaTypeName.get(receiver = receiver, returnType = UNIT)
}
