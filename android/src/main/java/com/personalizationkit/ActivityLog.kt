package com.personalizationkit

import com.google.gson.annotations.SerializedName
import java.util.Date
import java.util.UUID

/**
 * Model for activity logs - matches iOS PersonalizationKit ActivityLog.
 * Tracks user interactions like quiz completions, content views, etc.
 */
data class ActivityLog(
    @SerializedName("_id")
    val id: String = UUID.randomUUID().toString(),

    @SerializedName("activityId")
    val activityId: String,

    @SerializedName("type")
    val type: String,

    @SerializedName("value")
    val value: String? = null,

    @SerializedName("startDate")
    val startDate: Date = Date(),

    @SerializedName("completionDate")
    val completionDate: Date = Date(),

    @SerializedName("buildVersion")
    val buildVersion: String? = null,

    @SerializedName("sessionNumber")
    val sessionNumber: Int? = null,

    @SerializedName("learnerId")
    val learnerId: String? = null
)
