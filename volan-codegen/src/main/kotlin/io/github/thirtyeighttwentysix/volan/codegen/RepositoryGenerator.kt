package io.github.thirtyeighttwentysix.volan.codegen

import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.MAP
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.SET
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
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
            aggregate(model),
        )
    }

    private fun aggregate(model: Model): FunSpec = FunSpec.builder("aggregate")
        .addKdoc(
            "Works out summaries over the matching rows, in one statement.\n\n" +
                "Only what the block asks for comes back; reading anything else from the result says so.\n",
        )
        .addParameter("block", lambdaOn(types.declared("${model.name}AggregateScope")))
        .returns(types.declared("${model.name}Aggregate"))
        .addStatement(
            "return %T(executor.aggregate(%T().apply(block).build()))",
            types.declared("${model.name}Aggregate"),
            types.declared("${model.name}AggregateScope"),
        )
        .build()

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
        .addKdoc(
            "Inserts one row and reads it back.\n\n" +
                "Anything the block asks to write on the far side of a relation is written in the same " +
                "transaction, so either the whole shape lands or none of it does.\n",
        )
        .addParameter("block", lambdaOn(types.declared("${model.name}CreateData")))
        .returns(types.declared(model.name))
        .addStatement("val data = %T().apply(block)", types.declared("${model.name}CreateData"))
        .addStatement(
            "return executor.create(%T(%S, data.toValues(), %L), %T)",
            Types.createSpec,
            model.name,
            if (model.relationFields.isEmpty()) "emptyList()" else "data.toNested()",
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
        val client = types.declared("VolanClient")
        val builder = TypeSpec.classBuilder("VolanClient")
            .addKdoc(
                "The generated client for this schema.\n\n" +
                    "It targets `${schema.datasource.provider.id}` and hands out one repository per model. " +
                    "Everything it does goes through the executor it was given, which is what lets the same " +
                    "client run against a database, inside a transaction, or against a test double.\n",
            )
            .addSuperinterface(ClassName("java.lang", "AutoCloseable"))
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addModifiers(KModifier.PRIVATE)
                    .addParameter("connection", Types.volan.copy(nullable = true))
                    .addParameter("executor", Types.queryExecutor)
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("connection", Types.volan.copy(nullable = true))
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("connection")
                    .build(),
            )
            .addFunction(
                FunSpec.constructorBuilder()
                    .addKdoc("Builds a client over an executor, which is how a test puts a double in place of a database.\n")
                    .addParameter("executor", Types.queryExecutor)
                    .callThisConstructor("null", "executor")
                    .build(),
            )
            .addFunction(
                FunSpec.constructorBuilder()
                    .addKdoc("Builds a client over a connected database.\n")
                    .addParameter("connection", Types.volan)
                    .callThisConstructor("connection", "connection.executor")
                    .build(),
            )
        models.forEach { model ->
            builder.addProperty(
                PropertySpec.builder(model.name.replaceFirstChar { it.lowercase() }, types.declared("${model.name}Repository"))
                    .addKdoc("Reads and writes `${model.dbName}`.\n")
                    .initializer(CodeBlock.of("%T(executor)", types.declared("${model.name}Repository")))
                    .build(),
            )
        }
        return builder
            .addFunction(clientTransaction(client))
            .addFunctions(clientRawAccess())
            .addFunction(clientClose())
            .addType(clientCompanion(models))
            .addType(clientBuilder(client))
            .build()
    }

    private fun clientTransaction(client: ClassName): FunSpec = FunSpec.builder("transaction")
        .addKdoc(
            "Runs [block] in one transaction, committing when it returns and rolling back when it throws.\n\n" +
                "The client handed to the block is this one: statements find the transaction through the thread " +
                "that opened it, so nothing has to be threaded through by hand. A transaction inside a " +
                "transaction becomes a savepoint.\n\n" +
                "@throws io.github.thirtyeighttwentysix.volan.runtime.VolanConfigurationException if this client " +
                "was built over a bare executor rather than a connected database.\n",
        )
        .addAnnotation(ClassName("kotlin.jvm", "JvmOverloads"))
        .addTypeVariable(TypeVariableName("T"))
        .addParameter(
            ParameterSpec.builder("isolation", Types.isolation).defaultValue("%T.DEFAULT", Types.isolation).build(),
        )
        .addParameter(
            ParameterSpec.builder("retry", Types.retryPolicy).defaultValue("%T.NONE", Types.retryPolicy).build(),
        )
        .addParameter(
            "block",
            ClassName("java.util.function", "Function").parameterizedBy(client, TypeVariableName("T")),
        )
        .returns(TypeVariableName("T"))
        .addStatement(
            "val volan = connection ?: throw %T(%S)",
            Types.configurationException,
            "this client was built over an executor, not a database, so it has no transactions to open.",
        )
        .addStatement("return volan.transaction(isolation, retry) { block.apply(this) }")
        .build()

    /**
     * Raw SQL, for the questions a generated API does not ask.
     *
     * Values still travel as parameters: the escape hatch is the statement text, not the safety.
     */
    private fun clientRawAccess(): List<FunSpec> {
        val type = TypeVariableName("T")
        val database = CodeBlock.of(
            "connection ?: throw %T(%S)",
            Types.configurationException,
            "this client was built over an executor, not a database, so it cannot run raw SQL.",
        )
        return listOf(
            FunSpec.builder("rawQuery")
                .addKdoc(
                    "Runs a statement Volan did not build, and maps what comes back.\n\n" +
                        "Put values in [parameters] rather than in [sql]; that is what keeps this as safe as " +
                        "everything the client generates.\n",
                )
                .addTypeVariable(type)
                .addParameter("sql", STRING)
                .addParameter("parameters", LIST.parameterizedBy(ANY.copy(nullable = true)))
                .addParameter("mapper", Types.rowMapper.parameterizedBy(type))
                .returns(LIST.parameterizedBy(type))
                .addStatement("val database = %L", database)
                .addStatement("return database.rawQuery(sql, parameters, mapper)")
                .build(),
            FunSpec.builder("rawExecute")
                .addKdoc("Runs a statement Volan did not build, returning how many rows it changed.\n")
                .addParameter("sql", STRING)
                .addParameter(
                    ParameterSpec.builder("parameters", LIST.parameterizedBy(ANY.copy(nullable = true)))
                        .defaultValue("emptyList()")
                        .build(),
                )
                .returns(LONG)
                .addStatement("val database = %L", database)
                .addStatement("return database.rawExecute(sql, parameters)")
                .build(),
        )
    }

    private fun clientClose(): FunSpec = FunSpec.builder("close")
        .addKdoc("Closes the pool, when this client owns one.\n")
        .addModifiers(KModifier.OVERRIDE)
        .addStatement("connection?.close()")
        .build()

    private fun clientCompanion(models: List<Model>): TypeSpec {
        val tables = CodeBlock.builder().add("listOf(\n")
        models.forEach { tables.add("  %T.METADATA,\n", types.declared("${it.name}Table")) }
        tables.add(")")
        val readers = CodeBlock.builder().add("mapOf(\n")
        models.forEach { readers.add("  %S to %T,\n", it.name, types.declared("${it.name}RowMapper")) }
        readers.add(")")
        return TypeSpec.companionObjectBuilder()
            .addProperty(
                PropertySpec.builder("TABLES", LIST.parameterizedBy(Types.tableMetadata))
                    .addKdoc("What the runtime needs to know about this schema's models.\n")
                    .addAnnotation(ClassName("kotlin.jvm", "JvmField"))
                    .initializer(tables.build())
                    .build(),
            )
            .addProperty(
                PropertySpec.builder(
                    "READERS",
                    MAP.parameterizedBy(STRING, Types.entityReader.parameterizedBy(STAR)),
                )
                    .addKdoc("How to read each model, which is what loading a relation needs.\n")
                    .addAnnotation(ClassName("kotlin.jvm", "JvmField"))
                    .initializer(readers.build())
                    .build(),
            )
            .addFunction(
                FunSpec.builder("builder")
                    .addKdoc("Starts configuring a connection for this schema.\n")
                    .addAnnotation(ClassName("kotlin.jvm", "JvmStatic"))
                    .returns(types.declared("VolanClient").nestedClass("Builder"))
                    .addStatement("return Builder()")
                    .build(),
            )
            .build()
    }

    /**
     * A builder that carries the schema's tables for the caller, so connecting reads the way the
     * documentation shows rather than requiring two objects to be wired together.
     */
    private fun clientBuilder(client: ClassName): TypeSpec {
        val builderType = client.nestedClass("Builder")
        val options = listOf(
            Triple("url", STRING, "The JDBC URL to connect to. It also decides which dialect is used."),
            Triple("username", STRING.copy(nullable = true), "The user to connect as, when the URL does not carry it."),
            Triple("password", STRING.copy(nullable = true), "The password to connect with, when the URL does not carry it."),
            Triple("maxPoolSize", INT, "How many connections the pool may open."),
            Triple("connectionTimeout", LONG, "How long to wait for a connection from the pool, in milliseconds."),
            Triple("poolName", STRING, "The name the pool reports itself under."),
        )
        val builder = TypeSpec.classBuilder("Builder")
            .addKdoc("Configures a [%T].\n", client)
            .primaryConstructor(FunSpec.constructorBuilder().addModifiers(KModifier.INTERNAL).build())
            .addProperty(
                PropertySpec.builder("delegate", Types.volanBuilder)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("%T.builder().tables(TABLES).readers(READERS)", Types.volan)
                    .build(),
            )
        options.forEach { (name, type, kdoc) ->
            builder.addFunction(
                FunSpec.builder(name)
                    .addKdoc("%L\n", kdoc)
                    .addParameter("value", type)
                    .returns(builderType)
                    .addStatement("delegate.%L(value)", name)
                    .addStatement("return this")
                    .build(),
            )
        }
        return builder
            .addFunction(
                FunSpec.builder("clock")
                    .addKdoc("The clock `@updatedAt` columns are written from. Tests set this to make time stand still.\n")
                    .addParameter("value", ClassName("java.time", "Clock"))
                    .returns(builderType)
                    .addStatement("delegate.clock(value)")
                    .addStatement("return this")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("build")
                    .addKdoc("Opens the pool and returns a connected client.\n")
                    .returns(client)
                    .addStatement("return %T(delegate.build())", client)
                    .build(),
            )
            .build()
    }

    private fun lambdaOn(receiver: TypeName): TypeName = LambdaTypeName.get(receiver = receiver, returnType = UNIT)
}
