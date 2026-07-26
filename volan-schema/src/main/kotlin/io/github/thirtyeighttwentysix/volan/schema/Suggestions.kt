package io.github.thirtyeighttwentysix.volan.schema

/**
 * Finds the candidate a user most likely meant when they wrote something Volan does not recognise.
 *
 * This is what turns "unknown type `Strng`" into "did you mean `String`?", which is usually the whole
 * fix. It is deliberately conservative: a suggestion that is wrong more often than right teaches users
 * to ignore the `help:` line.
 */
public object Suggestions {
    private const val MAX_DISTANCE = 2

    /**
     * Returns the candidate closest to [word], or `null` when none is close enough to be worth
     * suggesting.
     *
     * Comparison ignores case, so `varchar` suggests `VarChar`. Ties are broken by the order of
     * [candidates], so callers control which of two equally close names is offered.
     */
    @JvmStatic
    public fun closest(word: String, candidates: List<String>): String? {
        val lowered = word.lowercase()
        return candidates
            .map { it to editDistance(lowered, it.lowercase()) }
            .filter { it.second <= MAX_DISTANCE }
            .minByOrNull { it.second }
            ?.first
    }

    /** The Levenshtein distance between [left] and [right], computed with two rolling rows. */
    private fun editDistance(left: String, right: String): Int {
        var previousRow = IntArray(right.length + 1) { it }
        var currentRow = IntArray(right.length + 1)
        for (i in 1..left.length) {
            currentRow[0] = i
            for (j in 1..right.length) {
                val substitution = previousRow[j - 1] + if (left[i - 1] == right[j - 1]) 0 else 1
                currentRow[j] = minOf(previousRow[j] + 1, currentRow[j - 1] + 1, substitution)
            }
            val swap = previousRow
            previousRow = currentRow
            currentRow = swap
        }
        return previousRow[right.length]
    }
}
