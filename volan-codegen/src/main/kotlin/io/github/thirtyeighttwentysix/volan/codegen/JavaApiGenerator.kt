package io.github.thirtyeighttwentysix.volan.codegen

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.joinToCode

/** Derives Java entry points from the very same functions that implement the Kotlin DSL. */
internal object JavaApiGenerator {
    private val jvmSynthetic = ClassName("kotlin.jvm", "JvmSynthetic")
    private val jvmOverloads = ClassName("kotlin.jvm", "JvmOverloads")
    private val nullMarked = ClassName("org.jspecify.annotations", "NullMarked")
    private val nullable = AnnotationSpec.builder(ClassName("org.jspecify.annotations", "Nullable")).build()
    private val consumer = ClassName("java.util.function", "Consumer")
    private val future = ClassName("java.util.concurrent", "CompletableFuture")

    fun adapt(type: TypeSpec, javaFriendly: Boolean): TypeSpec {
        val builder = type.toBuilder().addAnnotation(nullMarked)
        builder.funSpecs.clear()
        builder.propertySpecs.clear()
        builder.typeSpecs.clear()
        type.primaryConstructor?.let { builder.primaryConstructor(signature(it)) }
        type.propertySpecs.forEach { property ->
            builder.addProperty(property(property, javaFriendly))
            if (javaFriendly && property.type == UNIT && KModifier.PRIVATE !in property.modifiers) {
                builder.addFunction(
                    FunSpec.builder("${property.name}Java")
                        .addAnnotation(jvmName(property.name))
                        .addStatement("%N", property)
                        .build(),
                )
            }
        }
        type.funSpecs.forEach { function ->
            addFunction(builder, function, javaFriendly)
            if (isAsyncOperation(type, function)) addFunction(builder, async(function), javaFriendly)
        }
        type.typeSpecs.forEach { builder.addType(adapt(it, javaFriendly)) }
        return builder.build()
    }

    private fun isAsyncOperation(type: TypeSpec, function: FunSpec): Boolean =
        !function.isConstructor && KModifier.PRIVATE !in function.modifiers &&
            (
                type.name?.endsWith("Repository") == true ||
                    type.name == "VolanClient" && function.name in setOf("transaction", "rawQuery", "rawExecute")
                )

    private fun addFunction(builder: TypeSpec.Builder, function: FunSpec, javaFriendly: Boolean) {
        val hasReceiver = javaFriendly && function.parameters.any { it.type is LambdaTypeName }
        if (hasReceiver) {
            builder.addFunction(signature(function).toBuilder().addAnnotation(jvmSynthetic).build())
            builder.addFunction(javaFunction(function))
        } else {
            builder.addFunction(signature(function))
        }
    }

    private fun javaFunction(function: FunSpec): FunSpec {
        val builder = FunSpec.builder("${function.name}Java")
            .addKdoc("Java entry point for [%L]. The callback configures the same scope as the Kotlin DSL.\n", function.name)
            .addAnnotation(jvmName(function.name))
            .returns(nullability(function.returnType))
            .addTypeVariables(function.typeVariables)
        val arguments = function.parameters.map { parameter ->
            val lambda = parameter.type as? LambdaTypeName
            if (lambda == null) {
                builder.addParameter(parameter)
                CodeBlock.of("%N", parameter)
            } else {
                require(lambda.returnType == UNIT && lambda.receiver != null) { "Only configuration callbacks may use a receiver" }
                builder.addParameter(
                    ParameterSpec.builder(parameter.name, consumer.parameterizedBy(requireNotNull(lambda.receiver)))
                        .apply { if (parameter.defaultValue != null) defaultValue("%T {}", consumer) }
                        .build(),
                )
                CodeBlock.of("{ %N.accept(this) }", parameter)
            }
        }
        builder.addStatement("return %N(%L)", function, arguments.joinToCode(", "))
        return signature(builder.build())
    }

    private fun async(function: FunSpec): FunSpec = function.toBuilder("${function.name}Async")
        .apply {
            annotations.clear()
            clearBody()
            kdoc.clear()
        }
        .addKdoc(
            "Runs [%L] on the configured executor. The callback also runs there.\n\n" +
                "Use synchronous operations inside a transaction; dispatch from a transaction fails the future.\n",
            function.name,
        )
        .returns(future.parameterizedBy(function.returnType))
        .addStatement("return async.submit { %N(%L) }", function, function.parameters.joinToString(", ") { it.name })
        .build()

    private fun signature(function: FunSpec): FunSpec = function.toBuilder()
        .apply {
            parameters.clear()
            typeVariables.clear()
            function.typeVariables.forEach {
                addTypeVariable(TypeVariableName(it.name, it.bounds.map(::nullability)))
            }
            function.parameters.forEach { parameter ->
                addParameter(parameter.toBuilder(type = nullability(parameter.type)).build())
            }
            if (!function.isConstructor) returns(nullability(function.returnType))
            val hasDefaults = function.parameters.any { it.defaultValue != null }
            val javaCallable = function.parameters.none { it.type is LambdaTypeName } && KModifier.PRIVATE !in function.modifiers
            if (hasDefaults && javaCallable && function.annotations.none { it.typeName == jvmOverloads }) {
                addAnnotation(jvmOverloads)
            }
        }
        .build()

    private fun property(property: PropertySpec, javaFriendly: Boolean): PropertySpec =
        property.toBuilder(type = nullability(property.type))
            .apply {
                if (javaFriendly && (property.type == UNIT || KModifier.INTERNAL in property.modifiers)) {
                    addAnnotation(AnnotationSpec.builder(jvmSynthetic).useSiteTarget(AnnotationSpec.UseSiteTarget.GET).build())
                    if (property.mutable) {
                        addAnnotation(AnnotationSpec.builder(jvmSynthetic).useSiteTarget(AnnotationSpec.UseSiteTarget.SET).build())
                    }
                }
            }
            .build()

    private fun nullability(type: TypeName): TypeName {
        val nested = if (type is ParameterizedTypeName) {
            type.rawType.parameterizedBy(
                type.typeArguments.map(::nullability),
            ).copy(nullable = type.isNullable, annotations = type.annotations)
        } else {
            type
        }
        return if (nested.isNullable && nullable !in nested.annotations) {
            nested.copy(annotations = nested.annotations + nullable)
        } else {
            nested
        }
    }

    private fun jvmName(name: String): AnnotationSpec = AnnotationSpec.builder(ClassName("kotlin.jvm", "JvmName"))
        .addMember("%S", name)
        .build()
}
