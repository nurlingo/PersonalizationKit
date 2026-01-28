package com.personalizationkit

import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Service for managing activity history - matches iOS PersonalizationKit ActivityService.
 * Handles local storage and remote sync of activity logs.
 */
class ActivityService(
    private val preferences: SharedPreferences,
    private val gson: Gson = Gson()
) {
    private val historyMutex = Mutex()
    private var localActivityHistory: MutableList<ActivityLog>? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // Remote sync configuration
    private var analyticsUrl: String? = null
    private var apiKey: String? = null
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "ActivityService"
        private const val HISTORY_KEY = "engagement_history"
        private const val REPORTED_PREFIX = "activity_reported_"
    }

    /**
     * Configure remote sync.
     * @param serverUrl Base server URL (e.g., "https://namazlive.herokuapp.com")
     * @param collectionName Analytics collection name
     * @param apiKey API key for authentication
     */
    fun configureRemoteSync(serverUrl: String, collectionName: String, apiKey: String) {
        this.analyticsUrl = "$serverUrl/analytics/$collectionName"
        this.apiKey = apiKey
        Log.d(TAG, "Remote sync configured: $analyticsUrl")
    }

    /**
     * Initialize the activity service by loading local history.
     */
    fun kickstart() {
        scope.launch {
            historyMutex.withLock {
                localActivityHistory = retrieveLocalHistory()?.toMutableList() ?: mutableListOf()
                Log.d(TAG, "Local activity history loaded: ${localActivityHistory?.size ?: 0} items")
            }
        }
    }

    /**
     * Log an activity to local history and sync to remote.
     * Matches iOS ActivityService.logActivityToHistory().
     */
    fun logActivityToHistory(activityLog: ActivityLog) {
        scope.launch {
            var didAppend = false

            historyMutex.withLock {
                if (localActivityHistory == null) {
                    localActivityHistory = mutableListOf()
                }

                // Check if already exists
                if (localActivityHistory?.any { it.id == activityLog.id } == true) {
                    Log.d(TAG, "Activity already logged: ${activityLog.id}")
                    return@withLock
                }

                localActivityHistory?.add(activityLog)
                didAppend = true
                saveLocalHistory()
                Log.d(TAG, "Activity logged locally: ${activityLog.activityId} -> ${activityLog.value}")
            }

            // Sync to remote if configured (matches iOS behavior)
            if (didAppend && analyticsUrl != null && apiKey != null) {
                try {
                    logSingleActivityToRemote(activityLog)
                    markActivityReported(activityLog.id)
                    Log.d(TAG, "Activity synced to remote: ${activityLog.id}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to sync activity to remote: ${e.message}")
                    // Activity will be synced later in bulk upload
                }
            }
        }
    }

    /**
     * Sync a single activity to remote server.
     * Matches iOS logSingleActivitiesToRemoteHistory().
     */
    private suspend fun logSingleActivityToRemote(activityLog: ActivityLog) {
        val url = analyticsUrl ?: return
        val key = apiKey ?: return

        val jsonBody = gson.toJson(activityLog)
        val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .addHeader("x-api-key", key)
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Remote sync failed: ${response.code}")
        }
    }

    /**
     * Bulk upload unreported activities to remote.
     * Matches iOS logActivitiesToRemoteHistory().
     */
    fun bulkUploadActivitiesToRemote(minCount: Int = 1) {
        scope.launch {
            val url = analyticsUrl ?: return@launch
            val key = apiKey ?: return@launch

            val activitiesToUpload = mutableListOf<ActivityLog>()

            historyMutex.withLock {
                localActivityHistory?.sortedBy { it.startDate }?.forEach { log ->
                    if (!isActivityReported(log.id)) {
                        activitiesToUpload.add(log)
                        if (activitiesToUpload.size >= 500) return@forEach
                    }
                }
            }

            if (activitiesToUpload.size < minCount) {
                Log.d(TAG, "Not enough activities to bulk upload: ${activitiesToUpload.size}")
                return@launch
            }

            try {
                val jsonBody = gson.toJson(activitiesToUpload)
                val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

                val request = Request.Builder()
                    .url("$url/bulk")
                    .post(requestBody)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("x-api-key", key)
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    activitiesToUpload.forEach { markActivityReported(it.id) }
                    Log.d(TAG, "Bulk uploaded ${activitiesToUpload.size} activities")
                } else {
                    Log.e(TAG, "Bulk upload failed: ${response.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Bulk upload error", e)
            }
        }
    }

    /**
     * Get activity by ID, type, and/or value with different logic (max, min, first, last).
     */
    fun getActivity(
        activityId: String,
        type: String? = null,
        value: String? = null,
        logic: ValueLogic = ValueLogic.MAX
    ): ActivityLog? {
        val history = localActivityHistory ?: return null

        val filtered = history.filter {
            it.activityId == activityId &&
                    (type == null || it.type == type) &&
                    (value == null || it.value == value)
        }

        if (filtered.isEmpty()) return null

        return when (logic) {
            ValueLogic.FIRST -> filtered.first()
            ValueLogic.LAST -> filtered.last()
            ValueLogic.MAX -> filtered.maxByOrNull {
                it.value?.toDoubleOrNull() ?: Double.MIN_VALUE
            }
            ValueLogic.MIN -> filtered.minByOrNull {
                it.value?.toDoubleOrNull() ?: Double.MAX_VALUE
            }
        }
    }

    /**
     * Get all activities of specific types.
     */
    fun getActivities(types: List<String>): List<ActivityLog> {
        return localActivityHistory?.filter { types.contains(it.type) } ?: emptyList()
    }

    /**
     * Get all instances matching criteria.
     */
    fun getAllInstances(
        activityId: String? = null,
        type: String? = null,
        value: String? = null
    ): List<ActivityLog> {
        var result = localActivityHistory ?: return emptyList()

        activityId?.let { id ->
            result = result.filter { it.activityId == id }.toMutableList()
        }
        type?.let { t ->
            result = result.filter { it.type == t }.toMutableList()
        }
        value?.let { v ->
            result = result.filter { it.value == v }.toMutableList()
        }

        return result
    }

    /**
     * Get a summary of max values by activity ID.
     */
    fun getSummary(): Map<String, String> {
        val history = localActivityHistory ?: return emptyMap()

        return history.groupBy { it.activityId }
            .mapValues { (_, logs) ->
                // Try numeric max first
                val numericMax = logs.mapNotNull { it.value?.toDoubleOrNull() }.maxOrNull()
                if (numericMax != null) {
                    numericMax.toString()
                } else {
                    // Fall back to lexicographic max
                    logs.mapNotNull { it.value }.maxOrNull() ?: ""
                }
            }
    }

    /**
     * Check if an activity has been reported to remote.
     */
    fun isActivityReported(activityId: String): Boolean {
        return preferences.getBoolean("$REPORTED_PREFIX$activityId", false)
    }

    /**
     * Mark an activity as reported.
     */
    fun markActivityReported(activityId: String) {
        preferences.edit().putBoolean("$REPORTED_PREFIX$activityId", true).apply()
    }

    private fun saveLocalHistory() {
        try {
            val json = gson.toJson(localActivityHistory)
            preferences.edit().putString(HISTORY_KEY, json).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving local history", e)
        }
    }

    private fun retrieveLocalHistory(): List<ActivityLog>? {
        return try {
            val json = preferences.getString(HISTORY_KEY, null) ?: return null
            val type = object : TypeToken<List<ActivityLog>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving local history", e)
            null
        }
    }

    enum class ValueLogic {
        MAX, MIN, FIRST, LAST
    }
}
