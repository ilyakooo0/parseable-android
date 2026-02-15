package com.parseable.android.data

/** Escape single quotes in user input to prevent SQL injection. */
fun escapeSql(value: String): String = value.replace("'", "''")
