package io.github.thirtyeighttwentysix.volan

/**
 * The value of a `Json` column, held as the text the database stores.
 *
 * Volan deliberately does not parse it. Parsing would mean choosing a JSON library for everyone who
 * uses Volan, and pinning its version in their build; instead the raw text is handed over and callers
 * use whichever library they already have. A `TypeCodec` can map this to a richer type when that is
 * what an application wants.
 *
 * The type exists rather than using `String` so that a JSON column is distinguishable from a text
 * column in a signature, and so that JSON-aware filters have somewhere to hang.
 *
 * @property raw the JSON document exactly as stored.
 */
public class Json private constructor(public val raw: String) {
    override fun equals(other: Any?): Boolean = this === other || (other is Json && raw == other.raw)

    override fun hashCode(): Int = raw.hashCode()

    override fun toString(): String = raw

    public companion object {
        /** The JSON literal `null`, which is not the same as a null column. */
        @JvmField
        public val NULL: Json = Json("null")

        /**
         * Wraps [raw] as a JSON value.
         *
         * The text is not validated: the database is the authority on whether it is acceptable, and
         * validating twice would only disagree with it.
         */
        @JvmStatic
        public fun of(raw: String): Json = Json(raw)
    }
}
