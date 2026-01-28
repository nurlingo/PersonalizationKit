package com.personalizationkit

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.util.Date

/**
 * Analytics class - matches iOS PersonalizationKit Analytics.
 * Provides a unified interface for logging activities and managing user properties.
 *
 * Usage:
 *   PersonalizationKit.initialize(context)
 *   PersonalizationKit.analytics.logActivity(...)
 *   PersonalizationKit.analytics.setUserProperty(...)
 */
class Analytics(
    private val activityService: ActivityService,
    private val learnerService: LearnerService,
    private val preferences: SharedPreferences
) {
    companion object {
        private const val TAG = "Analytics"
        private const val LAUNCH_COUNT_KEY = "launch_count"
        private const val SESSION_NUMBER_KEY = "session_number"
    }

    /**
     * Current launch count.
     */
    val launchCount: Int
        get() = preferences.getInt(LAUNCH_COUNT_KEY, 0)

    /**
     * Current session number.
     */
    val currentSessionNumber: Int
        get() = preferences.getInt(SESSION_NUMBER_KEY, 0)

    /**
     * Increment the launch count and log the launch event.
     */
    fun incrementLaunchCount() {
        val newCount = launchCount + 1
        preferences.edit().putInt(LAUNCH_COUNT_KEY, newCount).apply()

        // Also increment session number
        val newSession = currentSessionNumber + 1
        preferences.edit().putInt(SESSION_NUMBER_KEY, newSession).apply()

        // Log the launch activity
        logActivity("launch", "event", newCount.toString())

        // Update user property
        setUserProperty(LAUNCH_COUNT_KEY, newCount.toString())

        Log.d(TAG, "Launch count incremented: $newCount")
    }

    /**
     * Log an activity - matches iOS Analytics.logActivity().
     *
     * @param activityId Unique identifier for the activity
     * @param type Type of activity (e.g., "event", "quiz_interaction", "content_view")
     * @param value Optional value associated with the activity
     * @param startDate When the activity started
     * @param completionDate When the activity completed
     */
    fun logActivity(
        activityId: String,
        type: String,
        value: String? = null,
        startDate: Date = Date(),
        completionDate: Date = Date()
    ) {
        Log.d(TAG, "📟 log: $type -> $activityId -> ${value?.take(30) ?: "nil"} | startDate: $startDate")

        val buildVersion = getBuildVersion()

        val activityLog = ActivityLog(
            activityId = activityId,
            type = type,
            value = value,
            startDate = startDate,
            completionDate = completionDate,
            buildVersion = buildVersion,
            sessionNumber = currentSessionNumber,
            learnerId = learnerService.learnerId
        )

        activityService.logActivityToHistory(activityLog)
    }

    /**
     * Set a user property - matches iOS Analytics.setUserProperty().
     */
    fun setUserProperty(property: String, value: Any) {
        Log.d(TAG, "💼 setUserProperty: $property | value: $value")

        val stringValue = when (value) {
            is Boolean -> if (value) "1" else "0"
            else -> value.toString()
        }

        learnerService.setProperty(property, stringValue)
    }

    /**
     * Get a user property - matches iOS Analytics.getUserProperty().
     */
    fun getUserProperty(property: String): String? {
        return learnerService.getProperty(property)
    }

    /**
     * Get activity by criteria.
     */
    fun getActivity(
        activityId: String,
        type: String? = null,
        value: String? = null,
        logic: ActivityService.ValueLogic = ActivityService.ValueLogic.MAX
    ): ActivityLog? {
        return activityService.getActivity(activityId, type, value, logic)
    }

    /**
     * Get all activities of specific types.
     */
    fun getActivities(types: List<String>): List<ActivityLog> {
        return activityService.getActivities(types)
    }

    /**
     * Get activity summary (max values by activity ID).
     */
    fun getSummary(): Map<String, String> {
        return activityService.getSummary()
    }

    private fun getBuildVersion(): String {
        return preferences.getString("build_version", "unknown") ?: "unknown"
    }
}

/**
 * Main entry point for PersonalizationKit - matches iOS pattern.
 * Initialize once at app startup, then access via PersonalizationKit.analytics, etc.
 */
object PersonalizationKit {
    private var _analytics: Analytics? = null
    private var _activityService: ActivityService? = null
    private var _learnerService: LearnerService? = null
    private var _preferences: SharedPreferences? = null

    val analytics: Analytics
        get() = _analytics ?: throw IllegalStateException("PersonalizationKit not initialized")

    val activityService: ActivityService
        get() = _activityService ?: throw IllegalStateException("PersonalizationKit not initialized")

    val learnerService: LearnerService
        get() = _learnerService ?: throw IllegalStateException("PersonalizationKit not initialized")

    val learnerId: String
        get() = learnerService.learnerId

    /**
     * Initialize PersonalizationKit. Call this once in Application.onCreate().
     *
     * @param context Application context
     * @param buildVersion Current app build version
     * @param serverUrl Server URL for remote sync (e.g., "https://namazlive.herokuapp.com")
     * @param learnerCollectionName Collection name for learner data
     * @param analyticsCollectionName Collection name for analytics data
     * @param apiKey API key for authentication
     */
    fun initialize(
        context: Context,
        buildVersion: String? = null,
        serverUrl: String? = null,
        learnerCollectionName: String? = null,
        analyticsCollectionName: String? = null,
        apiKey: String? = null
    ) {
        val prefs = context.getSharedPreferences("personalization_kit", Context.MODE_PRIVATE)
        _preferences = prefs

        // Store build version if provided
        buildVersion?.let {
            prefs.edit().putString("build_version", it).apply()
        }

        _activityService = ActivityService(prefs)
        _learnerService = LearnerService(prefs)
        _analytics = Analytics(_activityService!!, _learnerService!!, prefs)

        // Configure remote sync if server info provided
        if (serverUrl != null && apiKey != null) {
            if (analyticsCollectionName != null) {
                _activityService?.configureRemoteSync(serverUrl, analyticsCollectionName, apiKey)
            }
            if (learnerCollectionName != null) {
                _learnerService?.configureRemoteSync(serverUrl, learnerCollectionName, apiKey)
            }
        }

        // Kickstart the activity service
        _activityService?.kickstart()

        // Initialize learner with platform info
        buildVersion?.let {
            _learnerService?.initializeLearner(it)
        }

        Log.d("PersonalizationKit", "🎓 Initialized with learner ID: ${_learnerService?.learnerId}")
    }

    /**
     * Trigger bulk upload of unreported activities.
     * Call this periodically (e.g., on app background or every N minutes).
     */
    fun bulkUploadActivities(minCount: Int = 1) {
        _activityService?.bulkUploadActivitiesToRemote(minCount)
    }

    /**
     * Check if PersonalizationKit is initialized.
     */
    val isInitialized: Boolean
        get() = _analytics != null
}
