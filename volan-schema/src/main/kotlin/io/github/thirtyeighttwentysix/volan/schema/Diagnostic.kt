package io.github.thirtyeighttwentysix.volan.schema

/**
 * How serious a [Diagnostic] is.
 */
public enum class Severity {
    /** The schema cannot be used. Generation and migration refuse to run. */
    ERROR,

    /** The schema is usable, but something in it is suspicious or will stop working. */
    WARNING,
    ;

    /** The lower-case label used when rendering the diagnostic. */
    public val label: String
        get() = name.lowercase()
}

/**
 * A stable identifier for a class of schema problem.
 *
 * The identifiers are part of the user-visible contract: they appear in rendered diagnostics and in
 * the documentation, so they are never renumbered or reused. `E0xxx` codes come from the lexer,
 * `E01xx` codes from the parser.
 *
 * @property id the code as it appears in diagnostics, for example `E0104`.
 */
public enum class DiagnosticCode(public val id: String) {
    /** A string literal was opened but never closed before the end of the line. */
    UNTERMINATED_STRING("E0001"),

    /** A backslash inside a string literal was followed by a character that is not a valid escape. */
    INVALID_ESCAPE_SEQUENCE("E0002"),

    /** A character appeared that has no meaning anywhere in the schema language. */
    UNEXPECTED_CHARACTER("E0003"),

    /** A C-style block comment was used; the schema language only has line comments. */
    BLOCK_COMMENT_NOT_SUPPORTED("E0004"),

    /** A numeric literal is not well formed, for example `1.2.3`. */
    MALFORMED_NUMBER("E0005"),

    /** A top-level construct was expected but something else was found. */
    EXPECTED_DECLARATION("E0100"),

    /** A declaration, field or attribute name was expected. */
    EXPECTED_NAME("E0101"),

    /** A block was expected to open with `{`. */
    EXPECTED_OPENING_BRACE("E0102"),

    /** A block was opened but never closed. */
    UNCLOSED_BLOCK("E0103"),

    /** A field was declared without a type. */
    EXPECTED_FIELD_TYPE("E0104"),

    /** A configuration property was written without `=`. */
    EXPECTED_EQUALS("E0105"),

    /** A value was expected and something that cannot start one was found. */
    EXPECTED_EXPRESSION("E0106"),

    /** An attribute argument list was opened but never closed. */
    UNCLOSED_ARGUMENT_LIST("E0107"),

    /** A list was opened with `[` but never closed. */
    UNCLOSED_LIST("E0108"),

    /** A list type was marked optional, as in `Post[]?`. */
    OPTIONAL_LIST_TYPE("E0109"),

    /** A type was given more than one list marker, as in `Post[][]`. */
    NESTED_LIST_TYPE("E0110"),

    /** A type was given more than one optional marker, as in `String??`. */
    REPEATED_OPTIONAL_MARKER("E0111"),

    /** A field attribute was written with `@@`, or a block attribute with `@`. */
    WRONG_ATTRIBUTE_FORM("E0112"),

    /** An attribute name was expected after `@` or `@@`. */
    EXPECTED_ATTRIBUTE_NAME("E0113"),

    /** A member name was expected after `.` in a namespaced attribute such as `@db.VarChar`. */
    EXPECTED_ATTRIBUTE_MEMBER("E0114"),

    /** A named argument was written without a value. */
    EXPECTED_ARGUMENT_VALUE("E0115"),

    /** A token appeared at the top level where only declarations are allowed. */
    UNEXPECTED_TOP_LEVEL_TOKEN("E0116"),

    /** A model was declared without any fields. */
    EMPTY_MODEL("E0117"),

    /** An enum was declared without any values. */
    EMPTY_ENUM("E0118"),

    /** A configuration property was written without a value. */
    EXPECTED_CONFIGURATION_VALUE("E0119"),

    /** A block attribute was used inside a `datasource` or `generator` block, which does not allow them. */
    ATTRIBUTE_NOT_ALLOWED_HERE("E0120"),

    /** A token appeared inside a block where it has no meaning. */
    UNEXPECTED_TOKEN_IN_BLOCK("E0121"),

    /** A comment was written at a position where it cannot be attached to anything. */
    DANGLING_DOC_COMMENT("W0001"),
    ;

    override fun toString(): String = id
}

/**
 * One problem found in a schema document.
 *
 * A diagnostic points at an exact range of source text and, wherever the cause is actionable, carries
 * a [help] line telling the user what to write instead. Render it with [DiagnosticRenderer].
 *
 * @property severity how serious the problem is.
 * @property code the stable identifier for this class of problem.
 * @property message a one-line description, written in lower case and without a trailing full stop.
 * @property span the exact source range the problem concerns.
 * @property label a short annotation printed directly under the underlined text, if any.
 * @property help a suggested fix, printed after the code frame, if any.
 */
public data class Diagnostic(
    public val severity: Severity,
    public val code: DiagnosticCode,
    public val message: String,
    public val span: SourceSpan,
    public val label: String? = null,
    public val help: String? = null,
) {
    /** True when this diagnostic makes the schema unusable. */
    public val isError: Boolean
        get() = severity == Severity.ERROR

    override fun toString(): String = "${severity.label}[${code.id}] at $span: $message"
}
