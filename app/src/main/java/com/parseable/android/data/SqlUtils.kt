package com.parseable.android.data

/** Escape single quotes in user input to prevent SQL injection in string literals. */
fun escapeSql(value: String): String = value.replace("'", "''")

/** Escape a SQL identifier (table/column name) for use inside double quotes. */
fun escapeIdentifier(name: String): String = name.replace("\"", "\"\"")

/**
 * Escape a user-supplied substring for use as the body of a LIKE/ILIKE pattern.
 *
 * Besides the single-quote escaping every string literal needs, this neutralizes the
 * LIKE wildcards `%` and `_` (and the escape character `\` itself) so that searching or
 * filtering for a literal `%` or `_` matches that character instead of acting as a
 * wildcard. The backslash must be escaped first so the backslashes added for `%`/`_`
 * aren't doubled. Callers must pair the result with an `ESCAPE '\'` clause.
 */
fun escapeLikePattern(value: String): String =
    value
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")
        .replace("'", "''")
