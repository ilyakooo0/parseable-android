package com.parseable.android.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Saved filter synced with Parseable server via /api/v1/filters.
 */
@Serializable
data class SavedFilter(
    @SerialName("filter_id")
    val filterId: String? = null,
    @SerialName("filter_name")
    val filterName: String = "",
    @SerialName("stream_name")
    val streamName: String = "",
    @SerialName("user_id")
    val userId: String? = null,
    val version: String? = null,
    val query: SavedFilterQuery = SavedFilterQuery(),
    @SerialName("time_filter")
    val timeFilter: SavedFilterTimeFilter? = null,
)

@Serializable
data class SavedFilterQuery(
    @SerialName("filter_type")
    val filterType: String = "filter",
    @SerialName("filter_query")
    val filterQuery: String? = null,
    @SerialName("filter_builder")
    val filterBuilder: FilterBuilder? = null,
)

@Serializable
data class FilterBuilder(
    val id: String = "root",
    val combinator: String = "and",
    val rules: List<FilterRuleGroup> = emptyList(),
)

@Serializable
data class FilterRuleGroup(
    val id: String = "",
    val combinator: String = "and",
    val rules: List<FilterRule> = emptyList(),
)

@Serializable
data class FilterRule(
    val id: String = "",
    val field: String = "",
    val value: String = "",
    val operator: String = "=",
)

@Serializable
data class SavedFilterTimeFilter(
    val from: String? = null,
    val to: String? = null,
)
