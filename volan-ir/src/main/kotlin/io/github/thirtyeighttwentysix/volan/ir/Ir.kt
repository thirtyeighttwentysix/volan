package io.github.thirtyeighttwentysix.volan.ir

import io.github.thirtyeighttwentysix.volan.schema.SourceSpan

/**
 * A validated schema: everything a generator or a migration needs, with every name already resolved.
 *
 * Where the AST mirrors the file the user wrote, the IR mirrors the model it describes. Type names
 * have become [ScalarType]s, enum references and relations; both ends of every relation are linked;
 * `@map` has been applied so every model and field knows its database name. Nothing in the IR is
 * optional-because-we-did-not-check: if a schema produced one, it is valid.
 *
 * @property datasource the single datasource block.
 * @property generators the generator blocks, in source order.
 * @property enums the enum types, in source order.
 * @property models the models, in source order.
 * @property relations every relation, with both ends resolved, ordered by name.
 */
public data class Schema(
    public val datasource: DatasourceConfig,
    public val generators: List<GeneratorConfig>,
    public val enums: List<EnumType>,
    public val models: List<Model>,
    public val relations: List<Relation>,
) {
    private val modelsByName: Map<String, Model> = models.associateBy { it.name }
    private val enumsByName: Map<String, EnumType> = enums.associateBy { it.name }

    /** Returns the model called [name], or `null` if the schema has none. */
    public fun model(name: String): Model? = modelsByName[name]

    /** Returns the enum called [name], or `null` if the schema has none. */
    public fun enumType(name: String): EnumType? = enumsByName[name]

    /** Returns every relation that has an end on the model called [name]. */
    public fun relationsOf(name: String): List<Relation> = relations.filter { it.from.model == name || it.to.model == name }
}

/** The databases Volan can talk to. */
public enum class Provider(public val id: String) {
    /** PostgreSQL. */
    POSTGRESQL("postgresql"),

    /** MySQL. */
    MYSQL("mysql"),

    /** MariaDB. */
    MARIADB("mariadb"),

    /** SQLite. */
    SQLITE("sqlite"),

    /** H2. */
    H2("h2"),
    ;

    override fun toString(): String = id

    public companion object {
        /** Returns the provider whose id is [id], or `null` if there is none. */
        @JvmStatic
        public fun fromId(id: String): Provider? = entries.firstOrNull { it.id == id }

        /** Every provider id, in declaration order, for use in error messages. */
        @JvmStatic
        public fun ids(): List<String> = entries.map { it.id }
    }
}

/**
 * Where a connection URL comes from.
 */
public sealed interface ConnectionUrl {
    /** A URL written directly in the schema. */
    public data class Literal(public val value: String) : ConnectionUrl

    /** A URL read from the environment when the schema is used, which keeps secrets out of the repository. */
    public data class Environment(public val variable: String) : ConnectionUrl
}

/**
 * The resolved `datasource` block.
 *
 * @property name the block name, used only for readability.
 * @property provider which database this schema targets.
 * @property url where to get the connection URL.
 */
public data class DatasourceConfig(
    public val name: String,
    public val provider: Provider,
    public val url: ConnectionUrl,
    public val span: SourceSpan,
)

/**
 * A resolved `generator` block.
 *
 * @property name the block name.
 * @property provider which generator to run, for example `volan-kotlin`.
 * @property packageName the package the generated code goes into.
 * @property outputDirectory where the generated sources are written, relative to the project.
 * @property javaFriendly whether to generate the additional Java-facing API.
 */
public data class GeneratorConfig(
    public val name: String,
    public val provider: String,
    public val packageName: String,
    public val outputDirectory: String,
    public val javaFriendly: Boolean,
    public val span: SourceSpan,
)

/** The scalar types a field can have. */
public enum class ScalarType(public val schemaName: String) {
    /** Text. */
    STRING("String"),

    /** 32-bit signed integer. */
    INT("Int"),

    /** 64-bit signed integer. */
    LONG("Long"),

    /** 32-bit floating point. */
    FLOAT("Float"),

    /** 64-bit floating point. */
    DOUBLE("Double"),

    /** Exact decimal. */
    DECIMAL("Decimal"),

    /** Boolean. */
    BOOLEAN("Boolean"),

    /** A date and a time. */
    DATE_TIME("DateTime"),

    /** A date without a time. */
    DATE("Date"),

    /** A time without a date. */
    TIME("Time"),

    /** A JSON document. */
    JSON("Json"),

    /** Raw bytes. */
    BYTES("Bytes"),

    /** A UUID. */
    UUID("Uuid"),
    ;

    override fun toString(): String = schemaName

    public companion object {
        /** Returns the scalar type written as [schemaName], or `null` if there is none. */
        @JvmStatic
        public fun fromSchemaName(schemaName: String): ScalarType? = entries.firstOrNull { it.schemaName == schemaName }

        /** Every scalar type name, in declaration order, for use in error messages. */
        @JvmStatic
        public fun names(): List<String> = entries.map { it.schemaName }
    }
}

/** How many values a field holds, and whether it may hold none. */
public enum class Cardinality {
    /** Exactly one value. */
    REQUIRED,

    /** Zero or one value. */
    OPTIONAL,

    /** Any number of values. */
    LIST,
    ;

    /** True for [OPTIONAL], the only cardinality that allows null. */
    public val isOptional: Boolean
        get() = this == OPTIONAL

    /** True for [LIST]. */
    public val isList: Boolean
        get() = this == LIST
}

/** What a scalar field holds. */
public sealed interface FieldType {
    /** A built-in scalar. */
    public data class Scalar(public val type: ScalarType) : FieldType

    /** A value of an enum declared in the same schema. */
    public data class EnumRef(public val enumName: String) : FieldType
}

/**
 * A database-specific column type requested with `@db.…`.
 *
 * Whether the name is valid for the configured provider is decided by that provider's dialect, not
 * here: the schema layer only records what was asked for.
 *
 * @property name the type name, for example `VarChar`.
 * @property arguments its arguments as written, for example `["200"]`.
 */
public data class NativeType(public val name: String, public val arguments: List<String>)

/** The value a column takes when a write does not supply one. */
public sealed interface DefaultValue {
    /** A string constant. */
    public data class StringValue(public val value: String) : DefaultValue

    /** A numeric constant, kept as text so no precision is lost before the column type is known. */
    public data class NumberValue(public val value: String) : DefaultValue

    /** A boolean constant. */
    public data class BooleanValue(public val value: Boolean) : DefaultValue

    /** A member of an enum. */
    public data class EnumValueRef(public val enumName: String, public val valueName: String) : DefaultValue

    /** An empty list, the only default a list field may have. */
    public data object EmptyList : DefaultValue

    /** A database-assigned increasing integer. */
    public data object AutoIncrement : DefaultValue

    /** The moment the row is written. */
    public data object Now : DefaultValue

    /** A generated UUID. */
    public data object Uuid : DefaultValue

    /** A generated collision-resistant id. */
    public data object Cuid : DefaultValue

    /** An expression evaluated by the database itself. */
    public data class DatabaseGenerated(public val expression: String?) : DefaultValue
}

/** Something declared inside a model. */
public sealed interface Field {
    /** The name in the schema. */
    public val name: String

    /** The name in the database. */
    public val dbName: String

    /** How many values it holds. */
    public val cardinality: Cardinality

    /** Whether it is excluded from generation with `@ignore`. */
    public val isIgnored: Boolean

    /** The `///` documentation written above it, if any. */
    public val documentation: String?

    /** Where it was declared. */
    public val span: SourceSpan
}

/**
 * A field holding data: a scalar or an enum value, never a relation.
 *
 * @property type what the field holds.
 * @property isId whether it is the model's single-column primary key.
 * @property isUnique whether it carries a single-column unique constraint.
 * @property isUpdatedAt whether Volan overwrites it with the current time on every update.
 * @property default the value used when a write does not supply one.
 * @property nativeType the database-specific column type requested with `@db.…`.
 */
public data class ScalarField(
    override val name: String,
    override val dbName: String,
    public val type: FieldType,
    override val cardinality: Cardinality,
    public val isId: Boolean,
    public val isUnique: Boolean,
    public val isUpdatedAt: Boolean,
    override val isIgnored: Boolean,
    public val default: DefaultValue?,
    public val nativeType: NativeType?,
    override val documentation: String?,
    override val span: SourceSpan,
) : Field

/**
 * A field pointing at another model.
 *
 * The side that owns the foreign key carries [fields] and [references]; the other side carries
 * neither. For a many-to-many relation neither side does.
 *
 * @property targetModel the model on the other end.
 * @property relationName the relation this field belongs to, explicit or derived.
 * @property fields the local fields holding the foreign key, in order.
 * @property references the target fields they point at, in the same order.
 * @property onDelete what happens to this row when the referenced row is deleted.
 * @property onUpdate what happens to the foreign key when the referenced key changes.
 */
public data class RelationField(
    override val name: String,
    public val targetModel: String,
    override val cardinality: Cardinality,
    public val relationName: String,
    public val fields: List<String>,
    public val references: List<String>,
    public val onDelete: ReferentialAction?,
    public val onUpdate: ReferentialAction?,
    override val isIgnored: Boolean,
    override val documentation: String?,
    override val span: SourceSpan,
) : Field {
    /** The name in the database. A relation field is not itself a column, so this is its schema name. */
    override val dbName: String
        get() = name

    /** True when this side holds the foreign key. */
    public val isOwningSide: Boolean
        get() = fields.isNotEmpty()
}

/** What the database does to dependent rows when the row they reference changes. */
public enum class ReferentialAction(public val id: String) {
    /** Delete or update the dependent rows too. */
    CASCADE("Cascade"),

    /** Refuse the operation while dependent rows exist. */
    RESTRICT("Restrict"),

    /** Leave it to the database's own default behaviour. */
    NO_ACTION("NoAction"),

    /** Set the foreign key to null. */
    SET_NULL("SetNull"),

    /** Set the foreign key back to its default. */
    SET_DEFAULT("SetDefault"),
    ;

    override fun toString(): String = id

    public companion object {
        /** Returns the action written as [id], or `null` if there is none. */
        @JvmStatic
        public fun fromId(id: String): ReferentialAction? = entries.firstOrNull { it.id == id }

        /** Every action name, in declaration order, for use in error messages. */
        @JvmStatic
        public fun ids(): List<String> = entries.map { it.id }
    }
}

/**
 * A model's primary key.
 *
 * @property fields the key columns, in order.
 * @property dbName the constraint name, when the schema asked for a specific one.
 */
public data class PrimaryKey(public val fields: List<String>, public val dbName: String?)

/**
 * A unique constraint over one or more columns.
 *
 * @property fields the columns, in order.
 * @property dbName the constraint name, when the schema asked for a specific one.
 */
public data class UniqueConstraint(public val fields: List<String>, public val dbName: String?)

/** What kind of index to create. */
public enum class IndexKind {
    /** An ordinary index. */
    BTREE,

    /** A full-text index. */
    FULLTEXT,
}

/**
 * An index over one or more columns.
 *
 * @property fields the columns, in order.
 * @property dbName the index name, when the schema asked for a specific one.
 * @property kind what kind of index to create.
 */
public data class Index(
    public val fields: List<String>,
    public val dbName: String?,
    public val kind: IndexKind,
)

/**
 * A model: one table, its columns, its constraints and its links to other models.
 *
 * @property name the model name in the schema.
 * @property dbName the table name in the database.
 * @property fields the data-holding fields, in source order.
 * @property relationFields the fields pointing at other models, in source order.
 * @property primaryKey the primary key. Null only when [isIgnored] is true: a model Volan does not
 *   generate a client for may describe a table that has no key, which is how introspection represents
 *   tables it cannot address a single row of.
 * @property uniques the multi-column unique constraints declared with `@@unique`.
 * @property indexes the indexes declared with `@@index` and `@@fulltext`.
 * @property isIgnored whether the model is excluded from generation with `@@ignore`.
 */
public data class Model(
    public val name: String,
    public val dbName: String,
    public val fields: List<ScalarField>,
    public val relationFields: List<RelationField>,
    public val primaryKey: PrimaryKey?,
    public val uniques: List<UniqueConstraint>,
    public val indexes: List<Index>,
    public val isIgnored: Boolean,
    public val documentation: String?,
    public val span: SourceSpan,
) {
    private val fieldsByName: Map<String, ScalarField> = fields.associateBy { it.name }

    /** Returns the data field called [name], or `null` if the model has none. */
    public fun field(name: String): ScalarField? = fieldsByName[name]

    /** Returns the relation field called [name], or `null` if the model has none. */
    public fun relationField(name: String): RelationField? = relationFields.firstOrNull { it.name == name }

    /** The fields making up the primary key, resolved. Empty when the model has none. */
    public val primaryKeyFields: List<ScalarField>
        get() = primaryKey?.fields.orEmpty().mapNotNull { fieldsByName[it] }
}

/**
 * A value of an enum.
 *
 * @property name the name in the schema.
 * @property dbName the value stored in the database.
 */
public data class EnumValue(
    public val name: String,
    public val dbName: String,
    public val documentation: String?,
    public val span: SourceSpan,
)

/**
 * An enum type.
 *
 * @property name the name in the schema.
 * @property dbName the type name in databases that have native enums.
 * @property values its values, in source order.
 */
public data class EnumType(
    public val name: String,
    public val dbName: String,
    public val values: List<EnumValue>,
    public val documentation: String?,
    public val span: SourceSpan,
) {
    /** Returns the value called [name], or `null` if the enum has none. */
    public fun value(name: String): EnumValue? = values.firstOrNull { it.name == name }
}

/** The shape of a relation. */
public enum class RelationKind {
    /** At most one row on each side. */
    ONE_TO_ONE,

    /** Many rows on the list side, at most one on the other. */
    ONE_TO_MANY,

    /** Many rows on both sides, joined through a table Volan manages. */
    MANY_TO_MANY,
}

/**
 * One end of a relation.
 *
 * @property model the model this end belongs to.
 * @property field the relation field on that model.
 * @property cardinality how many rows this end can hold.
 */
public data class RelationEnd(
    public val model: String,
    public val field: String,
    public val cardinality: Cardinality,
)

/**
 * A relation with both ends resolved.
 *
 * For one-to-one and one-to-many relations, [from] is the end holding the foreign key. For
 * many-to-many relations neither end holds one and the ends are ordered by model name, so that the
 * generated join table is stable.
 *
 * @property name the relation name, explicit or derived from the two model names.
 * @property kind the shape of the relation.
 * @property foreignKeyFields the local fields holding the key, on the [from] side.
 * @property referencedFields the target fields they point at, on the [to] side.
 * @property joinTable the table Volan manages for a many-to-many relation, `null` otherwise.
 */
public data class Relation(
    public val name: String,
    public val kind: RelationKind,
    public val from: RelationEnd,
    public val to: RelationEnd,
    public val foreignKeyFields: List<String>,
    public val referencedFields: List<String>,
    public val onDelete: ReferentialAction?,
    public val onUpdate: ReferentialAction?,
    public val joinTable: String?,
) {
    /** True when both ends are on the same model. */
    public val isSelfRelation: Boolean
        get() = from.model == to.model
}
