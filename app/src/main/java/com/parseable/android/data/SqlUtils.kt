package com.parseable.android.data

/** Escape single quotes in user input to prevent SQL injection in string literals. */
fun escapeSql(value: String): String = value.replace("'", "''")

/** Escape a SQL identifier (table/column name) for use inside double quotes. */
fun escapeIdentifier(name: String): String = name.replace("\"", "\"\"")
