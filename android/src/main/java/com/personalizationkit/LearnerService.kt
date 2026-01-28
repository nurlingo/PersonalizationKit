package com.personalizationkit

import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Service for managing learner data - matches iOS PersonalizationKit LearnerService.
 * Handles learner ID generation, property storage, and remote sync.
 */
class LearnerService(
    private val preferences: SharedPreferences,
    private val gson: Gson = Gson()
) {
    companion object {
        private const val TAG = "LearnerService"
        private const val LEARNER_ID_KEY = "learner_id"
        private const val LEARNER_PROPERTIES_KEY = "learner_properties"
        private const val LAST_REMOTE_UPDATE_KEY = "last_learner_update"

        // Property keys
        const val PROP_PLATFORM = "platform"
        const val PROP_BUNDLE_VERSION_AT_INSTALL = "bundleVersionAtInstall"
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    // Remote sync configuration
    private var learnerUrl: String? = null
    private var apiKey: String? = null
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Configure remote sync.
     */
    fun configureRemoteSync(serverUrl: String, collectionName: String, apiKey: String) {
        this.learnerUrl = "$serverUrl/learner/$collectionName"
        this.apiKey = apiKey
        Log.d(TAG, "Remote sync configured: $learnerUrl")
    }

    /**
     * Get or create the learner ID.
     */
    val learnerId: String
        get() {
            var id = preferences.getString(LEARNER_ID_KEY, null)
            if (id == null) {
                id = UUID.randomUUID().toString()
                preferences.edit().putString(LEARNER_ID_KEY, id).apply()
                Log.d(TAG, "🎓 Created new learner ID: $id")
            }
            return id
        }

    /**
     * Initialize learner with platform info and build version.
     * Should be called once during app initialization.
     */
    fun initializeLearner(buildVersion: String) {
        // Set platform property (Android version info)
        val platformInfo = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
        if (getProperty(PROP_PLATFORM) == null) {
            setProperty(PROP_PLATFORM, platformInfo)
        }

        // Set bundle version at install (only if not already set)
        if (getProperty(PROP_BUNDLE_VERSION_AT_INSTALL) == null) {
            setProperty(PROP_BUNDLE_VERSION_AT_INSTALL, buildVersion)
        }

        Log.d(TAG, "🎓 Learner initialized: $learnerId, platform: $platformInfo")

        // Kickstart remote sync
        kickstartRemoteLearner()
    }

    /**
     * Start remote learner sync (fetch/create/update).
     * Matches iOS LearnerService.kickstartRemoteLearner().
     */
    fun kickstartRemoteLearner() {
        if (learnerUrl == null || apiKey == null) {
            Log.d(TAG, "Remote sync not configured, skipping")
            return
        }

        scope.launch {
            try {
                // Try to fetch existing remote learner
                val remoteLearner = getRemoteLearner()
                if (remoteLearner != null) {
                    // Merge remote properties with local (remote wins for server overrides)
                    mergeLearnerProperties(remoteLearner)
                    Log.d(TAG, "🎓 Merged with remote learner")
                } else {
                    // Create new remote learner
                    createRemoteLearner()
                    Log.d(TAG, "🎓 Created remote learner")
                }
            } catch (e: Exception) {
                Log.e(TAG, "🎓 Remote learner sync error", e)
                // Try to create if fetch failed
                try {
                    createRemoteLearner()
                } catch (e2: Exception) {
                    Log.e(TAG, "🎓 Failed to create remote learner", e2)
                }
            }
        }
    }

    /**
     * Get a learner property value.
     */
    fun getProperty(key: String): String? {
        val properties = loadProperties()
        return properties[key]
    }

    /**
     * Set a learner property value.
     */
    fun setProperty(key: String, value: String) {
        val properties = loadProperties().toMutableMap()
        properties[key] = value
        saveProperties(properties)
        Log.d(TAG, "Set learner property: $key = $value")

        // Sync to remote (throttled)
        syncToRemoteIfNeeded()
    }

    /**
     * Get all learner properties.
     */
    fun getAllProperties(): Map<String, String> {
        return loadProperties()
    }

    /**
     * Remove a learner property.
     */
    fun removeProperty(key: String) {
        val properties = loadProperties().toMutableMap()
        properties.remove(key)
        saveProperties(properties)
    }

    /**
     * Clear all learner properties.
     */
    fun clearProperties() {
        preferences.edit().remove(LEARNER_PROPERTIES_KEY).apply()
    }

    private fun loadProperties(): Map<String, String> {
        return try {
            val json = preferences.getString(LEARNER_PROPERTIES_KEY, null) ?: return emptyMap()
            val type = object : TypeToken<Map<String, String>>() {}.type
            gson.fromJson(json, type) ?: emptyMap()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading learner properties", e)
            emptyMap()
        }
    }

    private fun saveProperties(properties: Map<String, String>) {
        try {
            val json = gson.toJson(properties)
            preferences.edit().putString(LEARNER_PROPERTIES_KEY, json).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving learner properties", e)
        }
    }

    // Remote sync methods

    private fun syncToRemoteIfNeeded() {
        if (learnerUrl == null || apiKey == null) return

        // Throttle updates to once per minute (matches iOS)
        val lastUpdate = preferences.getLong(LAST_REMOTE_UPDATE_KEY, 0)
        val now = System.currentTimeMillis()
        if (now - lastUpdate < 60_000) return

        scope.launch {
            try {
                updateRemoteLearner()
                preferences.edit().putLong(LAST_REMOTE_UPDATE_KEY, now).apply()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync learner to remote", e)
            }
        }
    }

    private suspend fun getRemoteLearner(): LearnerData? {
        val url = learnerUrl ?: return null
        val key = apiKey ?: return null

        val request = Request.Builder()
            .url("$url/${learnerId.lowercase()}")
            .get()
            .addHeader("x-api-key", key)
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            if (response.code == 404) return null
            throw Exception("Get remote learner failed: ${response.code}")
        }

        val body = response.body?.string() ?: return null
        return gson.fromJson(body, LearnerData::class.java)
    }

    private suspend fun createRemoteLearner() {
        val url = learnerUrl ?: return
        val key = apiKey ?: return

        val learnerData = LearnerData(
            id = learnerId,
            properties = getAllProperties()
        )

        val jsonBody = gson.toJson(learnerData)
        val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .addHeader("x-api-key", key)
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Create remote learner failed: ${response.code}")
        }
        Log.d(TAG, "🎓 Remote learner created")
    }

    private suspend fun updateRemoteLearner() {
        val url = learnerUrl ?: return
        val key = apiKey ?: return

        val learnerData = LearnerData(
            id = learnerId,
            properties = getAllProperties()
        )

        val jsonBody = gson.toJson(learnerData)
        val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(url)
            .put(requestBody)
            .addHeader("Content-Type", "application/json")
            .addHeader("x-api-key", key)
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Update remote learner failed: ${response.code}")
        }
        Log.d(TAG, "🎓 Remote learner updated")
    }

    private fun mergeLearnerProperties(remote: LearnerData) {
        val localProps = loadProperties().toMutableMap()
        val serverOverrides = remote.serverOverrides ?: emptyMap()

        remote.properties.forEach { (key, value) ->
            val shouldOverride = serverOverrides[key] == true

            if (shouldOverride) {
                // Server override - force use remote value
                localProps[key] = value
            } else if (!localProps.containsKey(key)) {
                // Local doesn't have it - adopt remote
                localProps[key] = value
            }
            // If local has value and no override, keep local
        }

        saveProperties(localProps)
    }

    /**
     * Data class for remote learner sync.
     */
    data class LearnerData(
        val id: String,
        val properties: Map<String, String>,
        val serverOverrides: Map<String, Boolean>? = null
    )
}
