package jp.hi7.sshtermproto

import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.io.InputStreamReader
import java.io.OutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var editHost: EditText
    private lateinit var editPort: EditText
    private lateinit var editUser: EditText
    private lateinit var editPassword: EditText
    private lateinit var terminalView: TerminalView
    private lateinit var connectBar: android.view.View
    private lateinit var extraKeys: android.view.View
    private lateinit var extraKeysRow: LinearLayout
    private lateinit var btnCtrl: Button
    private lateinit var btnConnect: Button
    private lateinit var spinnerPresets: Spinner
    private lateinit var btnSavePreset: Button
    private lateinit var btnDeletePreset: Button
    private lateinit var lockOverlay: android.view.View
    private lateinit var lockStatus: TextView
    private lateinit var btnRetryUnlock: Button

    // "" represents "no preset selected" (just the free-typed / last-connection fields)
    private var presetNames: MutableList<String> = mutableListOf()

    // Device-credential auth is required every time this activity becomes
    // visible, not just on cold start — otherwise Home/Recents would bypass
    // it entirely. Not persisted: a fresh process always starts locked.
    private var unlocked = false

    private val scope = CoroutineScope(Dispatchers.Main)
    private var readJob: Job? = null

    private var ssh: SSHClient? = null
    private var session: Session? = null
    private var shell: Session.Shell? = null
    private var shellOut: OutputStream? = null

    // Everything that writes to the SSH channel (keystrokes, replies from the
    // terminal emulator, window-size changes) goes through this one queue and
    // is executed by a single coroutine. Launching a coroutine per keystroke on
    // Dispatchers.IO could reorder bytes across threads.
    private var ioChannel: Channel<() -> Unit>? = null
    private var ioJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep connection targets and command output out of Recents
        // thumbnails and screen recordings while the lock is relevant.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_main)

        editHost = findViewById(R.id.editHost)
        editPort = findViewById(R.id.editPort)
        editUser = findViewById(R.id.editUser)
        editPassword = findViewById(R.id.editPassword)
        terminalView = findViewById(R.id.terminalView)
        connectBar = findViewById(R.id.connectBar)
        extraKeys = findViewById(R.id.extraKeys)
        extraKeysRow = findViewById(R.id.extraKeysRow)
        btnConnect = findViewById(R.id.btnConnect)
        spinnerPresets = findViewById(R.id.spinnerPresets)
        btnSavePreset = findViewById(R.id.btnSavePreset)
        btnDeletePreset = findViewById(R.id.btnDeletePreset)
        lockOverlay = findViewById(R.id.lockOverlay)
        lockStatus = findViewById(R.id.lockStatus)
        btnRetryUnlock = findViewById(R.id.btnRetryUnlock)

        btnConnect.setOnClickListener { onConnectClicked() }
        btnSavePreset.setOnClickListener { onSavePresetClicked() }
        btnDeletePreset.setOnClickListener { onDeletePresetClicked() }
        btnRetryUnlock.setOnClickListener { requestUnlock() }

        // Terminal wiring. Input is forwarded byte-for-byte the moment it is
        // typed (the old "EditText + Send button" is gone; see TerminalView for
        // the design note). Dropped while locked so a hardware keyboard can't
        // type into a live session behind the lock overlay.
        terminalView.onInput = { bytes -> if (unlocked) sendToShell(bytes) }
        terminalView.onResize = { _, _, _, _ -> notifyWindowSize() }
        // Replies the emulator owes the host (cursor position, device attributes).
        terminalView.emulator.responder = { reply -> sendToShell(reply.toByteArray(Charsets.UTF_8)) }
        buildExtraKeys()

        refreshPresetSpinner(selectName = Presets.getLastPresetName(this))
        spinnerPresets.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val name = presetNames.getOrNull(position) ?: return
                if (name.isEmpty()) {
                    Presets.clearLastPresetName(this@MainActivity)
                    return
                }
                val preset = Presets.loadAll(this@MainActivity).firstOrNull { it.name == name } ?: return
                applyPresetToFields(preset)
                Presets.setLastPresetName(this@MainActivity, name)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // Nothing saved as a named preset yet? At least restore the last
        // successful connection's host/port/user so the fields aren't blank.
        if (Presets.getLastPresetName(this) == null) {
            Presets.loadLastConnection(this)?.let { applyPresetToFields(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!unlocked) {
            requestUnlock()
        }
    }

    override fun onPause() {
        super.onPause()
        // Require fresh auth next time this activity is shown again,
        // including a plain Home/Recents round-trip.
        unlocked = false
        lockOverlay.visibility = android.view.View.VISIBLE
    }

    private fun requestUnlock() {
        lockOverlay.visibility = android.view.View.VISIBLE
        btnRetryUnlock.visibility = android.view.View.GONE

        val biometricManager = BiometricManager.from(this)
        val allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

        when (biometricManager.canAuthenticate(allowed)) {
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                // Genuinely nothing to gate on this device: no lock screen,
                // no biometric hardware, ever. Proceed but say why.
                lockStatus.text = "端末に画面ロックが設定されていないため認証をスキップしました"
                unlockApp()
                return
            }
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
                // Transient — do NOT fail-open. Let the user retry.
                lockStatus.text = "認証デバイスが一時的に利用できません"
                btnRetryUnlock.visibility = android.view.View.VISIBLE
                return
            }
            BiometricManager.BIOMETRIC_SUCCESS -> { /* fall through to prompt */ }
            else -> {
                lockStatus.text = "認証を利用できません"
                btnRetryUnlock.visibility = android.view.View.VISIBLE
                return
            }
        }

        lockStatus.text = "端末のロック解除で認証してください"

        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                unlockApp()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // User cancel / back also lands here. Stay locked; offer retry.
                lockStatus.text = "認証失敗: $errString"
                btnRetryUnlock.visibility = android.view.View.VISIBLE
            }

            override fun onAuthenticationFailed() {
                lockStatus.text = "認証できませんでした。再試行してください"
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("SshTermProto")
            .setSubtitle("端末のロック解除で開きます")
            .setAllowedAuthenticators(allowed)
            .build()

        prompt.authenticate(promptInfo)
    }

    private fun unlockApp() {
        unlocked = true
        lockOverlay.visibility = android.view.View.GONE
    }

    private fun applyPresetToFields(preset: Preset) {
        editHost.setText(preset.host)
        editPort.setText(preset.port.toString())
        editUser.setText(preset.user)
    }

    private fun refreshPresetSpinner(selectName: String? = null) {
        val names = mutableListOf("")
        names.addAll(Presets.loadAll(this).map { it.name })
        presetNames = names
        val labels = names.map { if (it.isEmpty()) "(プリセット未選択)" else it }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerPresets.adapter = adapter
        val idx = if (selectName != null) names.indexOf(selectName).let { if (it < 0) 0 else it } else 0
        spinnerPresets.setSelection(idx, false)
    }

    private fun onSavePresetClicked() {
        val host = editHost.text.toString().trim()
        val port = editPort.text.toString().toIntOrNull() ?: 22
        val user = editUser.text.toString().trim()
        if (host.isEmpty() || user.isEmpty()) {
            appendLog("\n[save preset] host / username is required\n")
            return
        }
        if (!Presets.isValidPort(port)) {
            appendLog("\n[save preset] port must be 1-65535\n")
            return
        }

        val input = EditText(this)
        val currentName = presetNames.getOrNull(spinnerPresets.selectedItemPosition)?.takeIf { it.isNotEmpty() }
        input.setText(currentName ?: "$user@$host")

        val existingNames = Presets.loadAll(this).map { it.name }.toSet()

        AlertDialog.Builder(this)
            .setTitle("プリセット名")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    appendLog("\n[save preset] name is required\n")
                    return@setPositiveButton
                }
                if (name in existingNames) {
                    AlertDialog.Builder(this)
                        .setTitle("上書き確認")
                        .setMessage("プリセット「$name」は既に存在します。上書きしますか?")
                        .setPositiveButton("上書き") { _, _ -> doSavePreset(name, host, port, user) }
                        .setNegativeButton("キャンセル", null)
                        .show()
                } else {
                    doSavePreset(name, host, port, user)
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun doSavePreset(name: String, host: String, port: Int, user: String) {
        Presets.upsert(this, Preset(name, host, port, user))
        Presets.setLastPresetName(this, name)
        refreshPresetSpinner(selectName = name)
        appendLog("\n[preset saved] $name\n")
    }

    private fun onDeletePresetClicked() {
        val name = presetNames.getOrNull(spinnerPresets.selectedItemPosition)?.takeIf { it.isNotEmpty() }
        if (name == null) {
            appendLog("\n[delete preset] no preset selected\n")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("削除確認")
            .setMessage("プリセット「$name」を削除しますか?")
            .setPositiveButton("削除") { _, _ ->
                Presets.delete(this, name)
                refreshPresetSpinner()
                appendLog("\n[preset deleted] $name\n")
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    /** Prints a local status message into the terminal grid. */
    private fun appendLog(text: String) {
        // The emulator treats a bare LF as "down one row" (no carriage return),
        // so convert to CRLF like a tty would.
        terminalView.feed(text.replace("\n", "\r\n"))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * Builds the key row for keys a phone keyboard doesn't have. Buttons are
     * non-focusable so tapping them never steals focus (and the soft keyboard)
     * from the terminal view.
     */
    private fun buildExtraKeys() {
        fun add(label: String, onClick: () -> Unit): Button {
            val b = Button(this)
            b.text = label
            b.textSize = 12f
            b.isAllCaps = false
            b.minWidth = 0
            b.minimumWidth = 0
            b.minHeight = 0
            b.minimumHeight = 0
            b.setPadding(dp(10), 0, dp(10), 0)
            b.isFocusable = false
            b.setOnClickListener { onClick() }
            extraKeysRow.addView(
                b,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36))
            )
            return b
        }

        add("Esc") { terminalView.sendText("\u001b") }
        add("Tab") { terminalView.sendText("\t") }
        btnCtrl = add("Ctrl") { terminalView.ctrlArmed = !terminalView.ctrlArmed }
        terminalView.onCtrlStateChanged = { armed -> btnCtrl.text = if (armed) "Ctrl*" else "Ctrl" }
        add("^C") { terminalView.sendText("\u0003") }
        add("↑") { terminalView.sendKey(KeyEvent.KEYCODE_DPAD_UP) }
        add("↓") { terminalView.sendKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        add("←") { terminalView.sendKey(KeyEvent.KEYCODE_DPAD_LEFT) }
        add("→") { terminalView.sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
        add("Home") { terminalView.sendKey(KeyEvent.KEYCODE_MOVE_HOME) }
        add("End") { terminalView.sendKey(KeyEvent.KEYCODE_MOVE_END) }
        add("PgUp") { terminalView.sendKey(KeyEvent.KEYCODE_PAGE_UP) }
        add("PgDn") { terminalView.sendKey(KeyEvent.KEYCODE_PAGE_DOWN) }
        add("|") { terminalView.sendText("|") }
        add("~") { terminalView.sendText("~") }
        add("/") { terminalView.sendText("/") }
        add("-") { terminalView.sendText("-") }
        add("Alt+Enter") { terminalView.sendText("\u001b\r") } // newline in many TUIs
        add("Paste") { terminalView.paste() }
        add("KB") { terminalView.requestFocus(); terminalView.showKeyboard() }
        add("Disconnect") { disconnect() }
    }

    private fun disconnect() {
        if (shell == null) return
        closeConnection()
        appendLog("\n[disconnected]\n")
    }

    /** Queues a write to the SSH channel; ordering is preserved. */
    private fun sendToShell(bytes: ByteArray) {
        val out = shellOut ?: return
        ioChannel?.trySend({
            out.write(bytes)
            out.flush()
        })
    }

    /**
     * Tells the server the PTY is now cols x rows (sshj
     * Session.Shell.changeWindowDimensions, i.e. an SSH "window-change"
     * request). Called on every grid-size change: rotation, soft keyboard
     * show/hide, connect form hiding.
     */
    private fun notifyWindowSize() {
        val sh = shell ?: return
        val cols = terminalView.cols
        val rows = terminalView.rows
        val wPx = terminalView.width
        val hPx = terminalView.height
        ioChannel?.trySend({
            sh.changeWindowDimensions(cols, rows, wPx, hPx)
        })
    }

    private fun startIoWriter() {
        val ch = Channel<() -> Unit>(Channel.UNLIMITED)
        ioChannel = ch
        ioJob = scope.launch(Dispatchers.IO) {
            try {
                for (op in ch) {
                    op()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { appendLog("\n[send error] ${e.message}\n") }
            }
        }
    }

    private fun onConnectClicked() {
        if (shell != null) {
            appendLog("\n[already connected]\n")
            return
        }
        val host = editHost.text.toString().trim()
        val port = editPort.text.toString().toIntOrNull() ?: 22
        val user = editUser.text.toString().trim()
        val pass = editPassword.text.toString()

        if (host.isEmpty() || user.isEmpty()) {
            appendLog("\n[host / username is required]\n")
            return
        }
        if (!Presets.isValidPort(port)) {
            appendLog("\n[port must be 1-65535]\n")
            return
        }

        btnConnect.isEnabled = false
        appendLog("\nConnecting to $user@$host:$port ...\n")

        // Size of the character grid right now; used to allocate the PTY so the
        // remote app starts with the real dimensions. (The connect form is still
        // visible at this point, so it is corrected right after via
        // notifyWindowSize() once the form is hidden and the view re-laid out.)
        val ptyCols = terminalView.cols
        val ptyRows = terminalView.rows
        val ptyWidthPx = terminalView.width
        val ptyHeightPx = terminalView.height

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val client = SSHClient()
                    client.addHostKeyVerifier(PromiscuousVerifier())
                    client.connect(host, port)
                    client.authPassword(user, pass)
                    ssh = client

                    val sess = client.startSession()
                    // xterm-256color so TUIs (Claude Code, Codex, vim, htop ...)
                    // enable colour and cursor-addressing features.
                    sess.allocatePTY("xterm-256color", ptyCols, ptyRows, ptyWidthPx, ptyHeightPx, emptyMap())
                    session = sess

                    val sh = sess.startShell()
                    shell = sh
                    shellOut = sh.outputStream
                }
                // Remember host/port/user (never the password) only once the
                // connection actually succeeded — a failed attempt shouldn't
                // overwrite a good "last connection".
                Presets.saveLastConnection(this@MainActivity, host, port, user)
                // Drop leftovers of a previous session (alt screen, hidden
                // cursor, odd SGR state) before the new shell starts drawing.
                terminalView.reset()
                appendLog("[connected]\n")
                startIoWriter()
                startReadingOutput()

                // Give the terminal the whole screen while a session is active.
                connectBar.visibility = android.view.View.GONE
                extraKeys.visibility = android.view.View.VISIBLE
                notifyWindowSize()
                terminalView.requestFocus()
                terminalView.post { terminalView.showKeyboard() }
            } catch (e: Exception) {
                appendLog("\n[connect failed] ${e.message}\n")
                closeConnection()
            } finally {
                btnConnect.isEnabled = true
            }
        }
    }

    private fun startReadingOutput() {
        readJob = scope.launch(Dispatchers.IO) {
            try {
                val sh = shell ?: return@launch
                // InputStreamReader keeps partial UTF-8 sequences across reads.
                val reader = InputStreamReader(sh.inputStream, Charsets.UTF_8)
                val buf = CharArray(4096)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    val chunk = String(buf, 0, n)
                    // Raw remote output goes straight into the emulator (no
                    // CRLF conversion: the PTY already sends \r\n).
                    withContext(Dispatchers.Main) { terminalView.feed(chunk) }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { appendLog("\n[read error] ${e.message}\n") }
            }
            withContext(Dispatchers.Main) {
                appendLog("\n[connection closed]\n")
                closeConnection()
            }
        }
    }

    private fun closeConnection() {
        readJob?.cancel()
        readJob = null
        ioChannel?.close()
        ioChannel = null
        ioJob?.cancel()
        ioJob = null
        try { shell?.close() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        try { ssh?.disconnect() } catch (_: Exception) {}
        shell = null
        shellOut = null
        session = null
        ssh = null
        // Bring the connect form back so the user can reconnect.
        connectBar.visibility = android.view.View.VISIBLE
        extraKeys.visibility = android.view.View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        closeConnection()
    }
}
