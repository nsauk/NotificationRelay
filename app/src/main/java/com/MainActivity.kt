package xyz.nsauk.notificationrelay

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import android.app.Activity
import androidx.core.app.NotificationManagerCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

class MainActivity : Activity() {
    private lateinit var configEdit: EditText
    private lateinit var saveButton: Button
    private lateinit var enableButton: Button
    
    private val gson = Gson()
    private val configFile by lazy { File(filesDir, "config.json") }
    
    data class NotificationRule(
        val targetApp: String,
        val url: String,
        val extractionPattern: String? = null,
        val titlePattern: String? = null,
        val contentPattern: String? = null,
        val payloadMappings: Map<String, Map<String, Any>>? = null,
        val sendMetadata: Boolean = true
    )
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        initViews()
        loadConfig()
        updateStatus()
        
        enableButton.setOnClickListener { requestNotificationAccess() }
        saveButton.setOnClickListener { saveConfig() }
    }
    
    private fun initViews() {
        configEdit = findViewById(R.id.configEdit)
        saveButton = findViewById(R.id.saveButton)
        enableButton = findViewById(R.id.enableButton)
    }
    
    private fun loadConfig() {
        if (configFile.exists()) {
            try {
                val config = configFile.readText()
                configEdit.setText(config)
            } catch (e: Exception) {
                showDefaultConfig()
            }
        } else {
            showDefaultConfig()
        }
    }
    
    private fun showDefaultConfig() {
        val defaultRules = listOf(
            NotificationRule(
                targetApp = "com.example.app",
                url = "https://your-webhook-url.com/notify",
                titlePattern = ".*Order.*",
                extractionPattern = "Order #(\\w+)",
                sendMetadata = true,
                payloadMappings = mapOf(
                    "ABC123" to mapOf(
                        "customer" to "John Doe",
                        "priority" to "high",
                        "amount" to 299.99,
                        "department" to "Sales"
                    ),
                    "XYZ789" to mapOf(
                        "customer" to "Jane Smith",
                        "priority" to "normal",
                        "amount" to 150.00,
                        "department" to "Support"
                    )
                )
            )
        )
        configEdit.setText(gson.toJson(defaultRules))
    }

    private fun saveConfig() {
        try {
            val configText = configEdit.text.toString()
            val rules: List<NotificationRule> = gson.fromJson(
                configText, 
                object : TypeToken<List<NotificationRule>>() {}.type
            )
            
            configFile.writeText(configText)
            Toast.makeText(this, "Config saved successfully", Toast.LENGTH_SHORT).show()
            
        } catch (e: Exception) {
            Toast.makeText(this, "Invalid JSON format: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestNotificationAccess() {
        if (!isNotificationAccessGranted()) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        } else {
            Toast.makeText(this, "Notification access already granted", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isNotificationAccessGranted(): Boolean {
        val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(this)
        return enabledListeners.contains(packageName)
    }
    
    private fun updateStatus() {
        val hasAccess = isNotificationAccessGranted()

        if (hasAccess) {
            enableButton.text = "NOTIFICATION ACCESS GRANTED"
            enableButton.isEnabled = false
        } else {
            enableButton.text = "⚠︎ GRANT NOTIFICATION ACCESS"
            enableButton.isEnabled = true
        }
    }
    
    override fun onResume() {
        super.onResume()
        updateStatus()
    }
}
