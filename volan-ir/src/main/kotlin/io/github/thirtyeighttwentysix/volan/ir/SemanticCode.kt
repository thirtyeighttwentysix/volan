package io.github.thirtyeighttwentysix.volan.ir

import io.github.thirtyeighttwentysix.volan.schema.DiagnosticCode

/**
 * The problems semantic analysis can report: everything that makes a well-formed document describe a
 * model that cannot exist.
 *
 * The identifiers are part of the user-visible contract and are never renumbered or reused.
 *
 * @property id the code as it appears in diagnostics, for example `E0209`.
 */
public enum class SemanticCode(override val id: String) : DiagnosticCode {
    /** Two declarations share a name. */
    DUPLICATE_DECLARATION("E0200"),

    /** A field's type is neither a scalar, nor an enum, nor a model in this schema. */
    UNKNOWN_TYPE("E0201"),

    /** Two fields of one model, or two values of one enum, share a name. */
    DUPLICATE_MEMBER("E0202"),

    /** The schema has no `datasource` block. */
    MISSING_DATASOURCE("E0203"),

    /** The schema has more than one `datasource` block. */
    DUPLICATE_DATASOURCE("E0204"),

    /** A `provider` names a database Volan does not support. */
    UNKNOWN_PROVIDER("E0205"),

    /** A block is missing a property it cannot work without. */
    MISSING_OPTION("E0206"),

    /** A block declares a property that means nothing there. */
    UNKNOWN_OPTION("E0207"),

    /** A property has a value of the wrong kind. */
    INVALID_OPTION_VALUE("E0208"),

    /** A model declares no primary key. */
    MISSING_PRIMARY_KEY("E0209"),

    /** A model declares its primary key more than once. */
    DUPLICATE_PRIMARY_KEY("E0210"),

    /** A field cannot be part of a primary key. */
    INVALID_ID_FIELD("E0211"),

    /** An attribute name is not one Volan knows. */
    UNKNOWN_ATTRIBUTE("E0212"),

    /** An attribute was given an argument it does not take. */
    UNKNOWN_ATTRIBUTE_ARGUMENT("E0213"),

    /** An attribute is missing an argument it requires. */
    MISSING_ATTRIBUTE_ARGUMENT("E0214"),

    /** An attribute cannot be used on this kind of field or block. */
    INVALID_ATTRIBUTE_TARGET("E0215"),

    /** The same attribute was applied twice. */
    DUPLICATE_ATTRIBUTE("E0216"),

    /** A `@default` does not fit the field it is on. */
    INVALID_DEFAULT("E0217"),

    /** `@updatedAt` is on a field it cannot maintain. */
    INVALID_UPDATED_AT("E0218"),

    /** An attribute refers to a field the model does not have. */
    UNKNOWN_FIELD_REFERENCE("E0219"),

    /** A relation field has no matching field on the other model. */
    RELATION_MISSING_OPPOSITE("E0220"),

    /** Several relation fields could pair up, and nothing says which. */
    RELATION_AMBIGUOUS("E0221"),

    /** `fields` and `references` do not line up. */
    RELATION_ARGUMENT_MISMATCH("E0222"),

    /** `fields` and `references` are on the wrong side of the relation, or on both. */
    RELATION_INVALID_SIDE("E0223"),

    /** A relation points at fields that are not uniquely identifying. */
    RELATION_REFERENCE_NOT_UNIQUE("E0224"),

    /** A foreign key field has a different type from the field it references. */
    RELATION_TYPE_MISMATCH("E0225"),

    /** A referential action cannot be applied to this relation. */
    INVALID_REFERENTIAL_ACTION("E0226"),

    /** Two models map to one table, or two fields of a model map to one column. */
    DUPLICATE_MAPPED_NAME("E0227"),

    /** `@unique` is on a field that cannot carry it. */
    INVALID_UNIQUE_FIELD("E0229"),

    /** A constraint or index was declared over no fields. */
    EMPTY_FIELD_LIST("E0230"),

    /** A native type attribute uses a namespace other than `db`. */
    UNKNOWN_ATTRIBUTE_NAMESPACE("E0231"),

    /** A model requires itself, so no row of it could ever be inserted. */
    UNSATISFIABLE_REQUIRED_RELATION("E0232"),

    /** The foreign key of a one-to-one relation is not unique, so it could point at one row twice. */
    RELATION_FOREIGN_KEY_NOT_UNIQUE("E0233"),

    /** Deleting a row would cascade around a loop. Reported as a warning. */
    CASCADE_CYCLE("E0228"),

    /** A connection URL is written into the schema rather than read from the environment. Reported as a warning. */
    CONNECTION_URL_IN_SCHEMA("E0234"),
    ;

    override fun toString(): String = id
}
