package io.github.thirtyeighttwentysix.volan.schema.ast

import io.github.thirtyeighttwentysix.volan.schema.SourceSpan

/**
 * Base of every node in a parsed schema.
 *
 * Each node knows exactly which characters of the source it came from, which is what allows both
 * precise diagnostics and a formatter that can put comments back where the user wrote them.
 */
public sealed interface AstNode {
    /** The source range this node covers. */
    public val span: SourceSpan
}

/**
 * A node that can carry comments and remembers whether the user left a blank line above it.
 */
public sealed interface Documented {
    /** Comment lines written immediately above this node, in source order. */
    public val leadingComments: List<CommentLine>

    /** A comment written on the same line, after this node. */
    public val trailingComment: CommentLine?

    /** Whether at least one blank line separates this node from the one above it. */
    public val blankLineBefore: Boolean
}

/**
 * A single comment line.
 *
 * @property text the comment text with its `//` or `///` marker stripped and surrounding whitespace removed.
 * @property isDoc whether it was written as a `///` documentation comment.
 */
public data class CommentLine(
    public val text: String,
    public val isDoc: Boolean,
    override val span: SourceSpan,
) : AstNode

/**
 * A name as written in the source.
 *
 * @property text the name itself.
 */
public data class Identifier(public val text: String, override val span: SourceSpan) : AstNode {
    override fun toString(): String = text
}

/**
 * A whole schema document: every declaration it contains, in source order.
 *
 * @property declarations the datasource, generator, model and enum declarations, in the order written.
 * @property trailingComments comments at the end of the file that belong to no declaration.
 */
public data class SchemaDocument(
    public val declarations: List<Declaration>,
    public val trailingComments: List<CommentLine>,
    override val span: SourceSpan,
) : AstNode {
    /** Every `model` declaration, in source order. */
    public val models: List<ModelDeclaration>
        get() = declarations.filterIsInstance<ModelDeclaration>()

    /** Every `enum` declaration, in source order. */
    public val enums: List<EnumDeclaration>
        get() = declarations.filterIsInstance<EnumDeclaration>()

    /** Every `datasource` declaration, in source order. */
    public val datasources: List<DatasourceDeclaration>
        get() = declarations.filterIsInstance<DatasourceDeclaration>()

    /** Every `generator` declaration, in source order. */
    public val generators: List<GeneratorDeclaration>
        get() = declarations.filterIsInstance<GeneratorDeclaration>()
}

/** A top-level construct: `datasource`, `generator`, `model` or `enum`. */
public sealed interface Declaration :
    AstNode,
    Documented {
    /** The name given to the declaration. */
    public val name: Identifier

    /** Comments written at the end of the block, belonging to no member. */
    public val trailingComments: List<CommentLine>
}

/**
 * A `datasource` block: which database Volan talks to and how to reach it.
 *
 * @property entries the configuration properties, in source order.
 */
public data class DatasourceDeclaration(
    override val name: Identifier,
    public val entries: List<ConfigEntry>,
    override val leadingComments: List<CommentLine>,
    override val trailingComment: CommentLine?,
    override val blankLineBefore: Boolean,
    override val trailingComments: List<CommentLine>,
    override val span: SourceSpan,
) : Declaration

/**
 * A `generator` block: what Volan should produce from this schema, and where.
 *
 * @property entries the configuration properties, in source order.
 */
public data class GeneratorDeclaration(
    override val name: Identifier,
    public val entries: List<ConfigEntry>,
    override val leadingComments: List<CommentLine>,
    override val trailingComment: CommentLine?,
    override val blankLineBefore: Boolean,
    override val trailingComments: List<CommentLine>,
    override val span: SourceSpan,
) : Declaration

/**
 * A `model` block: one table's worth of fields plus the block attributes that describe it.
 *
 * @property members fields and block attributes, kept in the order they were written so that the
 *   formatter can reproduce the file faithfully.
 */
public data class ModelDeclaration(
    override val name: Identifier,
    public val members: List<ModelMember>,
    override val leadingComments: List<CommentLine>,
    override val trailingComment: CommentLine?,
    override val blankLineBefore: Boolean,
    override val trailingComments: List<CommentLine>,
    override val span: SourceSpan,
) : Declaration {
    /** The declared fields, in source order. */
    public val fields: List<FieldDeclaration>
        get() = members.filterIsInstance<FieldDeclaration>()

    /** The `@@` attributes declared on the model, in source order. */
    public val attributes: List<BlockAttributeDeclaration>
        get() = members.filterIsInstance<BlockAttributeDeclaration>()
}

/**
 * An `enum` block.
 *
 * @property members enum values and block attributes, in the order they were written.
 */
public data class EnumDeclaration(
    override val name: Identifier,
    public val members: List<EnumMember>,
    override val leadingComments: List<CommentLine>,
    override val trailingComment: CommentLine?,
    override val blankLineBefore: Boolean,
    override val trailingComments: List<CommentLine>,
    override val span: SourceSpan,
) : Declaration {
    /** The declared values, in source order. */
    public val values: List<EnumValueDeclaration>
        get() = members.filterIsInstance<EnumValueDeclaration>()

    /** The `@@` attributes declared on the enum, in source order. */
    public val attributes: List<BlockAttributeDeclaration>
        get() = members.filterIsInstance<BlockAttributeDeclaration>()
}

/** Something that can appear inside a `model` block. */
public sealed interface ModelMember :
    AstNode,
    Documented

/** Something that can appear inside an `enum` block. */
public sealed interface EnumMember :
    AstNode,
    Documented

/**
 * A configuration property inside a `datasource` or `generator` block, such as `url = env("DATABASE_URL")`.
 *
 * @property key the property name.
 * @property value the value assigned to it.
 */
public data class ConfigEntry(
    public val key: Identifier,
    public val value: Expression,
    override val leadingComments: List<CommentLine>,
    override val trailingComment: CommentLine?,
    override val blankLineBefore: Boolean,
    override val span: SourceSpan,
) : AstNode,
    Documented

/**
 * A field inside a `model` block, such as `email String @unique`.
 *
 * @property name the field name.
 * @property type the declared type together with its optionality or list marker.
 * @property attributes the `@` attributes applied to the field, in source order.
 */
public data class FieldDeclaration(
    public val name: Identifier,
    public val type: TypeReference,
    public val attributes: List<Attribute>,
    override val leadingComments: List<CommentLine>,
    override val trailingComment: CommentLine?,
    override val blankLineBefore: Boolean,
    override val span: SourceSpan,
) : ModelMember

/**
 * A value inside an `enum` block.
 *
 * @property name the value name.
 * @property attributes the `@` attributes applied to the value, in source order.
 */
public data class EnumValueDeclaration(
    public val name: Identifier,
    public val attributes: List<Attribute>,
    override val leadingComments: List<CommentLine>,
    override val trailingComment: CommentLine?,
    override val blankLineBefore: Boolean,
    override val span: SourceSpan,
) : EnumMember

/**
 * A `@@` attribute applied to a whole model or enum, such as `@@index([email, createdAt])`.
 *
 * @property name the attribute name.
 * @property arguments the arguments it was given, in source order.
 */
public data class BlockAttributeDeclaration(
    public val name: AttributeName,
    public val arguments: List<Argument>,
    override val leadingComments: List<CommentLine>,
    override val trailingComment: CommentLine?,
    override val blankLineBefore: Boolean,
    override val span: SourceSpan,
) : ModelMember,
    EnumMember

/**
 * An `@` attribute applied to a field or an enum value, such as `@default(now())` or `@db.VarChar(200)`.
 *
 * @property name the attribute name.
 * @property arguments the arguments it was given, in source order.
 */
public data class Attribute(
    public val name: AttributeName,
    public val arguments: List<Argument>,
    override val span: SourceSpan,
) : AstNode

/**
 * The name of an attribute, optionally qualified by a namespace as in `@db.VarChar`.
 *
 * @property namespace the part before the dot, if the name was qualified.
 * @property name the part after the dot, or the whole name when it was not qualified.
 */
public data class AttributeName(
    public val namespace: Identifier?,
    public val name: Identifier,
    override val span: SourceSpan,
) : AstNode {
    /** The name as written, including the namespace and dot when present. */
    public val qualifiedName: String
        get() = if (namespace == null) name.text else "${namespace.text}.${name.text}"

    override fun toString(): String = qualifiedName
}

/**
 * One argument of an attribute or function call, either positional or named as in `fields: [authorId]`.
 *
 * @property name the argument name, or `null` when the argument is positional.
 * @property value the value passed.
 */
public data class Argument(
    public val name: Identifier?,
    public val value: Expression,
    override val span: SourceSpan,
) : AstNode

/** How many values a field holds, and whether it may hold none. */
public enum class TypeArity {
    /** Exactly one value: `String`. */
    REQUIRED,

    /** Zero or one value: `String?`. */
    OPTIONAL,

    /** Any number of values: `Post[]`. */
    LIST,
}

/**
 * A field's declared type.
 *
 * The parser does not decide whether the type exists: `String`, `Role` and `Post` are all just names
 * here, and resolving them into scalars, enums and relations is the job of semantic analysis.
 *
 * @property name the type name as written.
 * @property arity whether the field is required, optional or a list.
 */
public data class TypeReference(
    public val name: Identifier,
    public val arity: TypeArity,
    override val span: SourceSpan,
) : AstNode {
    override fun toString(): String = when (arity) {
        TypeArity.REQUIRED -> name.text
        TypeArity.OPTIONAL -> "${name.text}?"
        TypeArity.LIST -> "${name.text}[]"
    }
}

/** A value written in a schema: a literal, a constant, a function call or a list of those. */
public sealed interface Expression : AstNode

/**
 * A double-quoted string.
 *
 * @property value the text with escape sequences resolved.
 * @property raw the literal exactly as written, including the quotes.
 */
public data class StringLiteral(
    public val value: String,
    public val raw: String,
    override val span: SourceSpan,
) : Expression

/**
 * A numeric literal, kept as text so that no precision is lost before the value's type is known.
 *
 * @property text the literal exactly as written.
 */
public data class NumberLiteral(public val text: String, override val span: SourceSpan) : Expression {
    /** Whether the literal was written without a decimal point. */
    public val isInteger: Boolean
        get() = !text.contains('.')
}

/**
 * `true` or `false`.
 *
 * @property value the boolean value.
 */
public data class BooleanLiteral(public val value: Boolean, override val span: SourceSpan) : Expression

/**
 * A bare name used as a value, such as the enum value in `@default(USER)` or a field name inside
 * `fields: [authorId]`.
 *
 * @property name the name as written.
 */
public data class ConstantReference(public val name: Identifier, override val span: SourceSpan) : Expression

/**
 * A call such as `now()`, `autoincrement()` or `env("DATABASE_URL")`.
 *
 * @property name the function name.
 * @property arguments the arguments passed, in source order.
 */
public data class FunctionCall(
    public val name: Identifier,
    public val arguments: List<Argument>,
    override val span: SourceSpan,
) : Expression

/**
 * A bracketed list such as `[email, createdAt]`.
 *
 * @property elements the elements, in source order.
 */
public data class ArrayLiteral(public val elements: List<Expression>, override val span: SourceSpan) : Expression
