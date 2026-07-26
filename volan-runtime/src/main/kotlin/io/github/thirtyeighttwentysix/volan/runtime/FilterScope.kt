package io.github.thirtyeighttwentysix.volan.runtime

/**
 * The receiver a generated `where { … }` block runs against.
 *
 * Conditions written one after another are combined with `AND`, which is what reading them top to
 * bottom suggests. `or { … }`, `and { … }` and `not { … }` open a nested scope of the same generated
 * type, so the model's fields stay available at any depth.
 *
 * Generated subclasses declare one field handle per column and call [record] for anything the handles
 * do not cover.
 */
public abstract class FilterScope protected constructor() {
    internal val conditions: MutableList<Filter> = ArrayList()

    /** Adds a condition to this scope. */
    protected fun record(filter: Filter) {
        conditions.add(filter)
    }

    /** Adds every condition of [nested], combined with `OR`. */
    protected fun recordAnyOf(nested: FilterScope) {
        Filter.any(nested.conditions)?.let { record(it) }
    }

    /** Adds every condition of [nested], combined with `AND`. */
    protected fun recordAllOf(nested: FilterScope) {
        Filter.all(nested.conditions)?.let { record(it) }
    }

    /** Adds the negation of everything [nested] collected. */
    protected fun recordNoneOf(nested: FilterScope) {
        Filter.all(nested.conditions)?.let { record(Filter.Not(it)) }
    }

    /** Adds a condition on the rows at the far end of a relation. */
    protected fun recordRelated(relation: String, quantifier: RelationQuantifier, nested: FilterScope) {
        record(Filter.Related(relation, quantifier, Filter.all(nested.conditions)))
    }

    /** Creates a handle for a text column. */
    protected fun textField(column: String): TextFilterField = TextFilterField(column, this)

    /** Creates a handle for a column whose values can be ordered. */
    protected fun <T : Comparable<T>> orderedField(column: String): OrderedFilterField<T> = OrderedFilterField(column, this)

    /** Creates a handle for a column that can only be compared for equality. */
    protected fun <T : Any> equalityField(column: String): EqualityFilterField<T> = EqualityFilterField(column, this)

    /** Creates a handle for an enum column, storing values as [toDatabaseValue] renders them. */
    protected fun <E : Enum<E>> enumField(column: String, toDatabaseValue: (E) -> String): EnumFilterField<E> =
        EnumFilterField(column, this, toDatabaseValue)

    /** Everything this scope collected, combined with `AND`, or `null` when it collected nothing. */
    public fun build(): Filter? = Filter.all(conditions)
}

/**
 * A column that can be compared for equality.
 *
 * @param T the type the column holds.
 */
public open class EqualityFilterField<T : Any> internal constructor(internal val column: String, private val scope: FilterScope) {
    /** Matches rows where the column equals [value]. */
    public infix fun eq(value: T) {
        add(Filter.Compare(column, ComparisonOperator.EQUAL, encode(value), matchMode))
    }

    /** Matches rows where the column does not equal [value]. */
    public infix fun notEq(value: T) {
        add(Filter.Compare(column, ComparisonOperator.NOT_EQUAL, encode(value), matchMode))
    }

    /** Matches rows where the column holds one of [values]. */
    public infix fun oneOf(values: Collection<T>) {
        add(Filter.InList(column, values.map { encode(it) }))
    }

    /** Matches rows where the column holds none of [values]. */
    public infix fun notOneOf(values: Collection<T>) {
        add(Filter.InList(column, values.map { encode(it) }, negated = true))
    }

    /** Matches rows where the column is null. */
    public fun isNull() {
        add(Filter.IsNull(column))
    }

    /** Matches rows where the column is not null. */
    public fun isNotNull() {
        add(Filter.IsNull(column, negated = true))
    }

    /** Converts a value to what the database stores. Overridden where the two differ, as for enums. */
    internal open fun encode(value: T): Any? = value

    /** How text comparisons on this handle treat case. Overridden by [TextFilterField.ignoringCase]. */
    internal open val matchMode: TextMatchMode
        get() = TextMatchMode.SENSITIVE

    internal fun add(filter: Filter) {
        scope.conditions.add(filter)
    }
}

/**
 * A column whose values can be put in order, so that ranges make sense.
 *
 * @param T the type the column holds.
 */
public open class OrderedFilterField<T : Comparable<T>> internal constructor(column: String, scope: FilterScope) :
    EqualityFilterField<T>(column, scope) {
    /** Matches rows below [value]. */
    public infix fun lt(value: T) {
        add(Filter.Compare(column, ComparisonOperator.LESS_THAN, encode(value)))
    }

    /** Matches rows at or below [value]. */
    public infix fun lte(value: T) {
        add(Filter.Compare(column, ComparisonOperator.LESS_THAN_OR_EQUAL, encode(value)))
    }

    /** Matches rows above [value]. */
    public infix fun gt(value: T) {
        add(Filter.Compare(column, ComparisonOperator.GREATER_THAN, encode(value)))
    }

    /** Matches rows at or above [value]. */
    public infix fun gte(value: T) {
        add(Filter.Compare(column, ComparisonOperator.GREATER_THAN_OR_EQUAL, encode(value)))
    }

    /** Matches rows from [lower] to [upper], both included. */
    public fun between(lower: T, upper: T) {
        add(Filter.Between(column, encode(lower), encode(upper)))
    }
}

/**
 * A text column, which can additionally be matched by prefix, suffix or substring, with or without
 * regard to case.
 */
public class TextFilterField internal constructor(
    column: String,
    scope: FilterScope,
    override val matchMode: TextMatchMode = TextMatchMode.SENSITIVE,
) : OrderedFilterField<String>(column, scope) {
    private val owner: FilterScope = scope

    /** Matches rows whose value contains [value]. */
    public infix fun contains(value: String) {
        add(Filter.Compare(column, ComparisonOperator.CONTAINS, value, matchMode))
    }

    /** Matches rows whose value starts with [value]. */
    public infix fun startsWith(value: String) {
        add(Filter.Compare(column, ComparisonOperator.STARTS_WITH, value, matchMode))
    }

    /** Matches rows whose value ends with [value]. */
    public infix fun endsWith(value: String) {
        add(Filter.Compare(column, ComparisonOperator.ENDS_WITH, value, matchMode))
    }

    /**
     * Returns a handle over the same column that ignores case.
     *
     * It applies to `eq`, `notEq`, `contains`, `startsWith` and `endsWith`. Membership tests
     * (`oneOf`, `notOneOf`) always compare exactly as stored.
     *
     * ```kotlin
     * where { email.ignoringCase() eq "Alice@Acme.com" }
     * ```
     */
    public fun ignoringCase(): TextFilterField = TextFilterField(column, owner, TextMatchMode.INSENSITIVE)
}

/**
 * An enum column. Values are compared as the database stores them, which is what `@map` on an enum
 * value changes.
 *
 * @param E the enum type.
 */
public class EnumFilterField<E : Enum<E>> internal constructor(
    column: String,
    scope: FilterScope,
    private val toDatabaseValue: (E) -> String,
) : EqualityFilterField<E>(column, scope) {
    override fun encode(value: E): Any = toDatabaseValue(value)
}
