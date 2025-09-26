package com.notificationforwarder

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

class NotificationForwarderService : NotificationListenerService() {
    
    private val TAG = "NotificationForwarder"
    private val gson = Gson()
    private val httpClient = OkHttpClient()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    data class NotificationRule(
        val targetApp: String,
        val url: String,
        val extractionPattern: String? = null,
        val titlePattern: String? = null,
        val contentPattern: String? = null,
        val valueMappings: Map<String, Map<String, Any>>? = null
    )
    
    data class NotificationPayload(
        val app: String,
        val title: String?,
        val content: String?,
        val timestamp: Long,
        val extractedData: String? = null,
        val mappedValues: Map<String, Any>? = null
    )
    
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        
        serviceScope.launch {
            try {
                processNotification(sbn)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing notification", e)
            }
        }
    }
    
    private suspend fun processNotification(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val notification = sbn.notification
        
        val title = notification.extras?.getCharSequence("android.title")?.toString()
        val content = notification.extras?.getCharSequence("android.text")?.toString()
        
        Log.d(TAG, "Notification from $packageName: $title - $content")
        
        val rules = loadRules()
        
        for (rule in rules) {
            if (shouldProcessNotification(rule, packageName, title, content)) {
                val payload = createPayload(rule, packageName, title, content)
                sendHttpRequest(rule.url, payload)
            }
        }
    }
    
    private fun shouldProcessNotification(
        rule: NotificationRule,
        packageName: String,
        title: String?,
        content: String?
    ): Boolean {
        // Check if app matches
        if (rule.targetApp != packageName) return false
        
        // Check title pattern if specified
        rule.titlePattern?.let { pattern ->
            if (title?.matches(Regex(pattern, RegexOption.IGNORE_CASE)) != true) {
                return false
            }
        }
        
        // Check content pattern if specified
        rule.contentPattern?.let { pattern ->
            if (content?.matches(Regex(pattern, RegexOption.IGNORE_CASE)) != true) {
                return false
            }
        }
        
        return true
    }
    
    private fun createPayload(
        rule: NotificationRule,
        packageName: String,
        title: String?,
        content: String?
    ): NotificationPayload {
        val extractedData = rule.extractionPattern?.let { pattern ->
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            val titleMatch = title?.let { regex.find(it) }
            val contentMatch = content?.let { regex.find(it) }
            
            titleMatch?.groupValues?.getOrNull(1) 
                ?: contentMatch?.groupValues?.getOrNull(1)
                ?: titleMatch?.value 
                ?: contentMatch?.value
        }
        
        // Look up mapped values based on extracted data
        val mappedValues = extractedData?.let { key ->
            rule.valueMappings?.get(key)?.also {
                Log.d(TAG, "Found mapping for '$key': $it")
            } ?: run {
                Log.d(TAG, "No mapping found for '$key'")
                null
            }
        }

        return NotificationPayload(
            app = packageName,
            title = title,
            content = content,
            timestamp = System.currentTimeMillis(),
            extractedData = extractedData,
            mappedValues = mappedValues
        )
    }
    
    private suspend fun sendHttpRequest(url: String, payload: NotificationPayload) {
        try {
            val json = gson.toJson(payload)
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = json.toRequestBody(mediaType)
            
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "NotificationForwarder/1.0")
                .build()
            
            withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d(TAG, "HTTP request successful: ${response.code}")
                    } else {
                        Log.w(TAG, "HTTP request failed: ${response.code} ${response.message}")
                    }
                }
            }
            
        } catch (e: IOException) {
            Log.e(TAG, "Network error sending HTTP request", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending HTTP request", e)
        }
    }
    
    private fun loadRules(): List<NotificationRule> {
        val configFile = File(filesDir, "config.json")
        
        return try {
            if (configFile.exists()) {
                val configText = configFile.readText()
                gson.fromJson(configText, object : TypeToken<List<NotificationRule>>() {}.type)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading rules", e)
            emptyList()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
