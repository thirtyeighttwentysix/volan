package io.github.thirtyeighttwentysix.volan.codegen

import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.SET
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import io.github.thirtyeighttwentysix.volan.ir.Model
import io.github.thirtyeighttwentysix.volan.ir.Schema

/**
 * Generates the repositories and the client that hands them out.
 *
 * A repository is a thin, typed front for the executor: it builds a description and passes it on. It
 * holds no state, opens no connection and knows no SQL.
 */
internal class RepositoryGenerator(private val types: TypeResolver) {
    fun repository(model: Model): TypeSpec = TypeSpec.classBuilder("${model.name}Repository")
        .addKdoc("Reads and writes `${model.dbName}`.\n")
        .primaryConstructor(FunSpec.constructorBuilder().addParameter("executor", Types.queryExecutor).build())
        .addProperty(
            PropertySpec.builder("executor", Types.queryExecutor).addModifiers(KModifier.PRIVATE).initializer("executor").build(),
        )
        .addFunctions(readFunctions(model))
        .addFunctions(writeFunctions(model))
        .addType(repositoryCompanion(model))
        .build()

    private fun readFunctions(model: Model): List<FunSpec> {
        val entity = types.declared(model.name)
        val query = types.declared("${model.name}Query")
        val mapper = types.declared("${model.name}RowMapper")
        val projection = types.declared("${model.name}Projection")
        return listOf(
            read("findMany", query, LIST.parameterizedBy(entity), "Reads every matching row.")
                .addStatement("return executor.findMany(%T().apply(block).build(), %T)", query, mapper).build(),
            read("findFirst", query, entity.copy(nullable = true), "Reads the first matching row, or `null`.")
                .addStatement("return executor.findFirst(%T().apply(block).build(), %T)", query, mapper).build(),
            orThrow("findFirst", model, entity, query, "matched the query"),
            read("findUnique", query, entity.copy(nullable = true), "Reads the row a unique key selects, or `null`.")
                .addStatement("return executor.findFirst(%T().apply(block).build(), %T)", query, mapper).build(),
            orThrow("findUnique", model, entity, query, "matched the unique key"),
            read("count", query, LONG, "Counts the matching rows.")
                .addStatement("return executor.count(%T().apply(block).build())", query).build(),
            read("exists", query, BOOLEAN, "Whether any row matches.")
                .addStatement("return executor.exists(%T().apply(block).build())", query).build(),
            project(
                "projectMany",
                model,
                LIST.parameterizedBy(projection),
                "findMany",
                "Reads only the selected fields of every matching row.",
            ),
            project(
                "projectFirst",
                model,
                projection.copy(nullable = true),
                "findFirst",
                "Reads only the selected fields of the first matching row.",
            ),
        )
    }

    private fun writeFunctions(model: Model): List<FunSpec> = listOf(
        create(model),
        createMany(model),
        update(model),
        updateMany(model),
        upsert(model),
        delete(model),
        deleteMany(model),
    )

    private fun read(name: String, query: TypeName, returns: TypeName, kdoc: String): FunSpec.Builder = FunSpec.builder(name)
        .addKdoc("%L\n", kdoc)
        .addParameter(ParameterSpec.builder("block", lambdaOn(query)).defaultValue("{}").build())
        .returns(returns)

    private fun orThrow(base: String, model: Model, entity: TypeName, query: TypeName, reason: String): FunSpec =
        FunSpec.builder("${base}OrThrow")
            .addKdoc(
                "Like [%L], but fails instead of returning `null`.\n\n" +
                    "@throws io.github.thirtyeighttwentysix.volan.runtime.VolanNotFoundException if nothing $reason.\n",
                base,
            )
            .addParameter(ParameterSpec.builder("block", lambdaOn(query)).defaultValue("{}").build())
            .returns(entity)
            .addStatement(
                "return %L(block) ?: throw %T(%S, %S)",
                base,
                Types.notFoundException,
                model.name,
                "no ${model.name} $reason",
            )
            .build()

    private fun project(name: String, model: Model, returns: TypeName, executorCall: String, kdoc: String): FunSpec {
        val query = types.declared("${model.name}Query")
        val projectionMapper = types.declared("${model.name}ProjectionMapper")
        return FunSpec.builder(name)
            .addKdoc(
                "%L\n\nFields the `select` block leaves out refuse to be read from the result.\n",
                kdoc,
            )
            .addParameter("block", lambdaOn(query))
            .returns(returns)
            .addStatement("val query = %T().apply(block)", query)
            .addStatement(
                "return executor.%L(query.build(), %T(query.selectedFields ?: ALL_FIELDS))",
                executorCall,
                projectionMapper,
            )
            .build()
    }

    private fun create(model: Model): FunSpec = FunSpec.builder("create")
        .addKdoc("Inserts one row and reads it back.\n")
        .addParameter("block", lambdaOn(types.declared("${model.name}CreateData")))
        .returns(types.declared(model.name))
        .addStatement(
            "return executor.create(%T(%S, %T().apply(block).toValues()), %T)",
            Types.createSpec,
            model.name,
            types.declared("${model.name}CreateData"),
            types.declared("${model.name}RowMapper"),
        )
        .build()

    private fun createMany(model: Model): FunSpec = FunSpec.builder("createMany")
        .addKdoc("Inserts several rows, returning how many were written.\n")
        .addParameter("block", lambdaOn(types.declared("${model.name}CreateMany")))
        .returns(LONG)
        .addStatement("return executor.createMany(%T().apply(block).rows)", types.declared("${model.name}CreateMany"))
        .build()

    private fun update(model: Model): FunSpec = FunSpec.builder("update")
        .addKdoc("Changes the single row the `where` block selects and reads it back.\n")
        .addParameter("block", lambdaOn(types.declared("${model.name}UpdateScope")))
        .returns(types.declared(model.name))
        .addStatement("val scope = %T().apply(block)", types.declared("${model.name}UpdateScope"))
        .addStatement(
            "return executor.update(%T(%S, scope.filter.build(), scope.payload.toValues()), %T)",
            Types.updateSpec,
            model.name,
            types.declared("${model.name}RowMapper"),
        )
        .build()

    private fun updateMany(model: Model): FunSpec = FunSpec.builder("updateMany")
        .addKdoc("Changes every row the `where` block selects, returning how many changed.\n")
        .addParameter("block", lambdaOn(types.declared("${model.name}UpdateScope")))
        .returns(LONG)
        .addStatement("val scope = %T().apply(block)", types.declared("${model.name}UpdateScope"))
        .addStatement(
            "return executor.updateMany(%T(%S, scope.filter.build(), scope.payload.toValues()))",
            Types.updateSpec,
            model.name,
        )
        .build()

    private fun upsert(model: Model): FunSpec = FunSpec.builder("upsert")
        .addKdoc("Changes the row the `where` block selects, or inserts one when there is none.\n")
        .addParameter("block", lambdaOn(types.declared("${model.name}UpsertScope")))
        .returns(types.declared(model.name))
        .addStatement("val scope = %T().apply(block)", types.declared("${model.name}UpsertScope"))
        .addStatement(
            "return executor.upsert(%T(%S, scope.filter.build(), scope.insert.toValues(), scope.patch.toValues()), %T)",
            Types.upsertSpec,
            model.name,
            types.declared("${model.name}RowMapper"),
        )
        .build()

    private fun delete(model: Model): FunSpec = FunSpec.builder("delete")
        .addKdoc("Deletes the single row the `where` block selects and reads back what was removed.\n")
        .addParameter("block", lambdaOn(types.declared("${model.name}DeleteScope")))
        .returns(types.declared(model.name))
        .addStatement("val scope = %T().apply(block)", types.declared("${model.name}DeleteScope"))
        .addStatement(
            "return executor.delete(%T(%S, scope.filter.build()), %T)",
            Types.deleteSpec,
            model.name,
            types.declared("${model.name}RowMapper"),
        )
        .build()

    private fun deleteMany(model: Model): FunSpec = FunSpec.builder("deleteMany")
        .addKdoc("Deletes every row the `where` block selects, returning how many were removed.\n")
        .addParameter("block", lambdaOn(types.declared("${model.name}DeleteScope")))
        .returns(LONG)
        .addStatement("val scope = %T().apply(block)", types.declared("${model.name}DeleteScope"))
        .addStatement("return executor.deleteMany(%T(%S, scope.filter.build()))", Types.deleteSpec, model.name)
        .build()

    private fun repositoryCompanion(model: Model): TypeSpec {
        val fields = model.fields.joinToString(", ") { "\"${it.name}\"" }
        return TypeSpec.companionObjectBuilder()
            .addProperty(
                PropertySpec.builder("ALL_FIELDS", SET.parameterizedBy(STRING))
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("setOf(%L)", fields)
                    .build(),
            )
            .build()
    }

    fun client(schema: Schema, models: List<Model>): TypeSpec {
        val builder = TypeSpec.classBuilder("VolanClient")
            .addKdoc(
                "The generated client for this schema.\n\n" +
                    "It targets `${schema.datasource.provider.id}` and hands out one repository per model. " +
                    "Everything it does goes through the executor it was given.\n",
            )
            .primaryConstructor(FunSpec.constructorBuilder().addParameter("executor", Types.queryExecutor).build())
            .addProperty(
                PropertySpec.builder("executor", Types.queryExecutor).addModifiers(KModifier.PRIVATE).initializer("executor").build(),
            )
        models.forEach { model ->
            builder.addProperty(
                PropertySpec.builder(model.name.replaceFirstChar { it.lowercase() }, types.declared("${model.name}Repository"))
                    .addKdoc("Reads and writes `${model.dbName}`.\n")
                    .initializer(CodeBlock.of("%T(executor)", types.declared("${model.name}Repository")))
                    .build(),
            )
        }
        return builder.build()
    }

    private fun lambdaOn(receiver: TypeName): TypeName = LambdaTypeName.get(receiver = receiver, returnType = UNIT)
}
