package xyz.nsauk.notificationrelay

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.io.IOException

class NotificationForwarderService : NotificationListenerService() {
    
    private val TAG = "NotificationRelay"
    private val gson = Gson()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    data class NotificationRule(
        val targetApp: String,
        val url: String,
        val extractionPattern: String? = null,
        val titlePattern: String? = null,
        val contentPattern: String? = null,
        val payloadMappings: Map<String, Map<String, Any>>? = null,
        val sendMetadata: Boolean = true
    )
    
    data class NotificationMetadata(
        val app: String,
        val title: String?,
        val content: String?,
        val timestamp: Long,
        val extractedData: String? = null
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
                if (payload.isNotEmpty()) {
                    sendHttpRequest(rule.url, payload)
                } else {
                    Log.d(TAG, "Skipping HTTP request - empty payload")
                }
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
    ): Map<String, Any?> {
        val extractedData = rule.extractionPattern?.let { pattern ->
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            val titleMatch = title?.let { regex.find(it) }
            val contentMatch = content?.let { regex.find(it) }
            
            titleMatch?.groupValues?.getOrNull(1) 
                ?: contentMatch?.groupValues?.getOrNull(1)
                ?: titleMatch?.value 
                ?: contentMatch?.value
        }
        
        val payload = mutableMapOf<String, Any?>()

        if (rule.payloadMappings != null && extractedData != null) {
            var mappedValues: Map<String, Any>?

            // Try exact key match first
            mappedValues = rule.payloadMappings[extractedData]

            // If no exact match, try regex patterns
            if (mappedValues == null) {
                for ((pattern, values) in rule.payloadMappings) {
                    try {
                        val regex = Regex(pattern, RegexOption.IGNORE_CASE)
                        val match = regex.find(extractedData)
                        if (match != null) {
                            // Replace placeholders like {1}, {2} with capture groups
                            mappedValues = values.mapValues { (_, value) ->
                                if (value is String) {
                                    match.groupValues.foldIndexed(value) { index, acc, group ->
                                        acc.replace("{$index}", group)
                                    }
                                } else {
                                    value
                                }
                            }
                            Log.d(TAG, "Regex match for pattern '$pattern' with '$extractedData': $mappedValues")
                            break
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Invalid regex pattern: $pattern", e)
                    }
                }
            }

            if (mappedValues != null) {
                payload.putAll(mappedValues)
                Log.d(TAG, "Using mapped payload: $mappedValues")
            } else {
                Log.d(TAG, "No mapping found for '$extractedData'")
                return emptyMap()
            }
        }

        if (rule.sendMetadata) {
            val metadata = NotificationMetadata(
                app = packageName,
                title = title,
                content = content,
                timestamp = System.currentTimeMillis(),
                extractedData = extractedData
            )
            payload["metadata"] = metadata
        }

        if (rule.payloadMappings == null) {
            payload["app"] = packageName
            payload["title"] = title
            payload["content"] = content
            payload["timestamp"] = System.currentTimeMillis()
            if (extractedData != null) {
                payload["extractedData"] = extractedData
            }
        }

        return payload
    }

    private suspend fun sendHttpRequest(url: String, payload: Map<String, Any?>) {
        try {
            withContext(Dispatchers.IO) {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", "NotificationRelay/1.0")
                    doOutput = true
                    connectTimeout = 10000
                    readTimeout = 10000
                }

                val json = gson.toJson(payload)
                connection.outputStream.use { outputStream ->
                    outputStream.write(json.toByteArray())
                }

                val responseCode = connection.responseCode
                if (responseCode in 200..299) {
                    Log.d(TAG, "HTTP request successful: $responseCode")
                    Log.d(TAG, "Payload sent: $json")
                } else {
                    Log.w(TAG, "HTTP request failed: $responseCode ${connection.responseMessage}")
                }

                connection.disconnect()
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
