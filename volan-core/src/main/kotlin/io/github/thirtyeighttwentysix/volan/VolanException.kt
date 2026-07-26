package io.github.thirtyeighttwentysix.volan

/**
 * Root of every exception Volan throws.
 *
 * Catching this type catches everything Volan can raise, from a schema parse failure at build time to
 * a unique-constraint violation at runtime. Subclasses are declared in the module that owns the
 * failure: schema problems in `volan-schema`, query and connection problems in `volan-runtime`, and so
 * on.
 *
 * Every message names what failed and, where the cause is actionable, how to fix it.
 */
public abstract class VolanException : RuntimeException {
    /**
     * Creates an exception with the given [message].
     */
    protected constructor(message: String) : super(message)

    /**
     * Creates an exception with the given [message], caused by [cause].
     */
    protected constructor(message: String, cause: Throwable?) : super(message, cause)
}
