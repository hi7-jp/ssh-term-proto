package jp.hi7.sshtermproto

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var editHost: EditText
    private lateinit var editPort: EditText
    private lateinit var editUser: EditText
    private lateinit var editPassword: EditText
    private lateinit var editCommand: EditText
    private lateinit var textLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var btnConnect: Button
    private lateinit var btnSend: Button

    private val scope = CoroutineScope(Dispatchers.Main)
    private var readJob: Job? = null

    private var ssh: SSHClient? = null
    private var session: Session? = null
    private var shell: Session.Shell? = null
    private var shellOut: OutputStream? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editHost = findViewById(R.id.editHost)
        editPort = findViewById(R.id.editPort)
        editUser = findViewById(R.id.editUser)
        editPassword = findViewById(R.id.editPassword)
        editCommand = findViewById(R.id.editCommand)
        textLog = findViewById(R.id.textLog)
        scrollLog = findViewById(R.id.scrollLog)
        btnConnect = findViewById(R.id.btnConnect)
        btnSend = findViewById(R.id.btnSend)

        btnConnect.setOnClickListener { onConnectClicked() }
        btnSend.setOnClickListener { sendCommand() }
        editCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCommand()
                true
            } else {
                false
            }
        }
    }

    private fun appendLog(text: String) {
        textLog.append(text)
        scrollLog.post { scrollLog.fullScroll(android.view.View.FOCUS_DOWN) }
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

        btnConnect.isEnabled = false
        appendLog("\nConnecting to $user@$host:$port ...\n")

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val client = SSHClient()
                    client.addHostKeyVerifier(PromiscuousVerifier())
                    client.connect(host, port)
                    client.authPassword(user, pass)
                    ssh = client

                    val sess = client.startSession()
                    sess.allocateDefaultPTY()
                    session = sess

                    val sh = sess.startShell()
                    shell = sh
                    shellOut = sh.outputStream
                }
                appendLog("[connected]\n")
                startReadingOutput()
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
                val reader = BufferedReader(InputStreamReader(sh.inputStream))
                val buf = CharArray(4096)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    val chunk = String(buf, 0, n)
                    withContext(Dispatchers.Main) { appendLog(chunk) }
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

    private fun sendCommand() {
        val cmd = editCommand.text.toString()
        val out = shellOut
        if (out == null) {
            appendLog("\n[not connected]\n")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                out.write((cmd + "\n").toByteArray())
                out.flush()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { appendLog("\n[send error] ${e.message}\n") }
            }
        }
        editCommand.setText("")
    }

    private fun closeConnection() {
        readJob?.cancel()
        readJob = null
        try { shell?.close() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        try { ssh?.disconnect() } catch (_: Exception) {}
        shell = null
        shellOut = null
        session = null
        ssh = null
    }

    override fun onDestroy() {
        super.onDestroy()
        closeConnection()
    }
}
