package io.github.thirtyeighttwentysix.volan.codegen

import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.BYTE_ARRAY
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.DOUBLE
import com.squareup.kotlinpoet.FLOAT
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.MAP
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import io.github.thirtyeighttwentysix.volan.ir.Cardinality
import io.github.thirtyeighttwentysix.volan.ir.FieldType
import io.github.thirtyeighttwentysix.volan.ir.ScalarType

/** The runtime and JDK types generated code refers to, named once so a rename is one edit. */
internal object Types {
    private const val RUNTIME = "io.github.thirtyeighttwentysix.volan.runtime"
    private const val CORE = "io.github.thirtyeighttwentysix.volan"

    val json: ClassName = ClassName(CORE, "Json")
    val bigDecimal: ClassName = ClassName("java.math", "BigDecimal")
    val instant: ClassName = ClassName("java.time", "Instant")
    val localDate: ClassName = ClassName("java.time", "LocalDate")
    val localTime: ClassName = ClassName("java.time", "LocalTime")
    val uuid: ClassName = ClassName("java.util", "UUID")

    val filterScope: ClassName = ClassName(RUNTIME, "FilterScope")
    val orderScope: ClassName = ClassName(RUNTIME, "OrderScope")
    val selectScope: ClassName = ClassName(RUNTIME, "SelectScope")
    val aggregateScope: ClassName = ClassName(RUNTIME, "AggregateScope")
    val aggregateFunction: ClassName = ClassName(RUNTIME, "AggregateFunction")
    val aggregateValues: ClassName = ClassName(RUNTIME, "AggregateValues")
    val includeScope: ClassName = ClassName(RUNTIME, "IncludeScope")
    val queryScope: ClassName = ClassName(RUNTIME, "QueryScope")
    val orderField: ClassName = ClassName(RUNTIME, "OrderField")
    val textFilterField: ClassName = ClassName(RUNTIME, "TextFilterField")
    val orderedFilterField: ClassName = ClassName(RUNTIME, "OrderedFilterField")
    val equalityFilterField: ClassName = ClassName(RUNTIME, "EqualityFilterField")
    val enumFilterField: ClassName = ClassName(RUNTIME, "EnumFilterField")
    val relationQuantifier: ClassName = ClassName(RUNTIME, "RelationQuantifier")
    val relationSlot: ClassName = ClassName(RUNTIME, "RelationSlot")
    val selectedFields: ClassName = ClassName(RUNTIME, "SelectedFields")
    val row: ClassName = ClassName(RUNTIME, "Row")
    val rowMapper: ClassName = ClassName(RUNTIME, "RowMapper")
    val entityReader: ClassName = ClassName(RUNTIME, "EntityReader")
    val queryExecutor: ClassName = ClassName(RUNTIME, "QueryExecutor")
    val querySpec: ClassName = ClassName(RUNTIME, "QuerySpec")
    val createSpec: ClassName = ClassName(RUNTIME, "CreateSpec")
    val nestedWrite: ClassName = ClassName(RUNTIME, "NestedWrite")
    val nestedWrites: ClassName = ClassName(RUNTIME, "NestedWrites")
    val connectOrCreateEntry: ClassName = ClassName(RUNTIME, "ConnectOrCreateEntry")
    val updateSpec: ClassName = ClassName(RUNTIME, "UpdateSpec")
    val deleteSpec: ClassName = ClassName(RUNTIME, "DeleteSpec")
    val upsertSpec: ClassName = ClassName(RUNTIME, "UpsertSpec")
    val tableMetadata: ClassName = ClassName(RUNTIME, "TableMetadata")
    val columnMetadata: ClassName = ClassName(RUNTIME, "ColumnMetadata")
    val relationMetadata: ClassName = ClassName(RUNTIME, "RelationMetadata")
    val notFoundException: ClassName = ClassName(RUNTIME, "VolanNotFoundException")
    val mappingException: ClassName = ClassName(RUNTIME, "VolanMappingException")
    val validationException: ClassName = ClassName(RUNTIME, "VolanValidationException")
    val configurationException: ClassName = ClassName(RUNTIME, "VolanConfigurationException")
    val volan: ClassName = ClassName(RUNTIME, "Volan")
    val volanBuilder: ClassName = volan.nestedClass("Builder")
    val isolation: ClassName = ClassName(RUNTIME, "Isolation")
    val retryPolicy: ClassName = ClassName(RUNTIME, "RetryPolicy")

    /** `Map<String, Any?>`, the shape write specs carry their values in. */
    val valueMap: TypeName = MAP.parameterizedBy(STRING, ANY.copy(nullable = true))

    /** The Kotlin type a scalar column maps to. */
    fun scalar(type: ScalarType): TypeName = when (type) {
        ScalarType.STRING -> STRING
        ScalarType.INT -> INT
        ScalarType.LONG -> LONG
        ScalarType.FLOAT -> FLOAT
        ScalarType.DOUBLE -> DOUBLE
        ScalarType.DECIMAL -> bigDecimal
        ScalarType.BOOLEAN -> BOOLEAN
        ScalarType.DATE_TIME -> instant
        ScalarType.DATE -> localDate
        ScalarType.TIME -> localTime
        ScalarType.JSON -> json
        ScalarType.BYTES -> BYTE_ARRAY
        ScalarType.UUID -> uuid
    }

    /** The `Row` accessor that reads a scalar column, without the `OrNull` suffix. */
    fun rowAccessor(type: ScalarType): String = when (type) {
        ScalarType.STRING -> "getString"
        ScalarType.INT -> "getInt"
        ScalarType.LONG -> "getLong"
        ScalarType.FLOAT -> "getFloat"
        ScalarType.DOUBLE -> "getDouble"
        ScalarType.DECIMAL -> "getDecimal"
        ScalarType.BOOLEAN -> "getBoolean"
        ScalarType.DATE_TIME -> "getInstant"
        ScalarType.DATE -> "getLocalDate"
        ScalarType.TIME -> "getLocalTime"
        ScalarType.JSON -> "getJson"
        ScalarType.BYTES -> "getBytes"
        ScalarType.UUID -> "getUuid"
    }

    /**
     * The `AggregateValues` reader that turns a summary back into this type.
     *
     * Drivers hand out their own idea of a timestamp or a UUID, so a summary is read through the same
     * kind of conversion a row goes through rather than cast straight to the field's type.
     */
    fun aggregateReader(type: ScalarType): String = when (type) {
        ScalarType.STRING -> "string"
        ScalarType.INT -> "int"
        ScalarType.LONG -> "long"
        ScalarType.FLOAT -> "float"
        ScalarType.DOUBLE -> "double"
        ScalarType.DECIMAL -> "decimal"
        ScalarType.DATE_TIME -> "instant"
        ScalarType.DATE -> "localDate"
        ScalarType.TIME -> "localTime"
        ScalarType.UUID -> "uuid"
        ScalarType.BOOLEAN, ScalarType.JSON, ScalarType.BYTES -> error("${type.name} has no smallest or largest value")
    }

    /** Whether values of this type can be put in order, which decides the filter handle it gets. */
    fun isOrdered(type: ScalarType): Boolean = when (type) {
        ScalarType.JSON, ScalarType.BYTES, ScalarType.BOOLEAN -> false
        else -> true
    }
}

/** Resolves the declared type of a field into the Kotlin type the entity exposes. */
internal class TypeResolver(private val packageName: String) {
    /** The type a scalar or enum field holds, honouring optionality and lists. */
    fun fieldType(type: FieldType, cardinality: Cardinality): TypeName {
        val base = elementType(type)
        return when (cardinality) {
            Cardinality.REQUIRED -> base
            Cardinality.OPTIONAL -> base.copy(nullable = true)
            Cardinality.LIST -> LIST.parameterizedBy(base)
        }
    }

    /** The type of a single value of the field, ignoring optionality and lists. */
    fun elementType(type: FieldType): TypeName = when (type) {
        is FieldType.Scalar -> Types.scalar(type.type)
        is FieldType.EnumRef -> ClassName(packageName, type.enumName)
    }

    /** The generated type for a model, enum or other declaration of the schema. */
    fun declared(name: String): ClassName = ClassName(packageName, name)
}
