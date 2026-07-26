package io.github.thirtyeighttwentysix.volan.dialect

import io.github.thirtyeighttwentysix.volan.VolanException

/**
 * Thrown when a schema asks for something the chosen database cannot express.
 *
 * The message names what was asked for and what to write instead, because the answer is always a
 * change to the schema rather than a change to the query.
 */
public class VolanDialectException(message: String) : VolanException(message)
