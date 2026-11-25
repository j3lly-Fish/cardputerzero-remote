package cc.cpz.remote

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

private const val TAG = "cpz"

class MainActivity : Activity(), Connection.Listener {
    private lateinit var hostField: EditText
    private lateinit var pinField: EditText
    private lateinit var connectBtn: Button
    private lateinit var scanBtn: Button
    private lateinit var status: TextView
    private lateinit var screen: ScreenView
    private lateinit var keyboard: KeyboardView

    private var conn: Connection? = null
    private val bt: BluetoothAdapter? by lazy { getSystemService(BluetoothManager::class.java)?.adapter }
    private var afterPermissions: (() -> Unit)? = null
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("cpz", MODE_PRIVATE) }

    // Hardware/IME key state: which Android keys we wrapped in Shift, and whether a real Shift is down.
    private val wrappedInShift = HashSet<Int>()
    private var hwShiftDown = false
    private var imeShownFallback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bg = Color.rgb(0x0E, 0x10, 0x13)
        val pad = dp(8f).toInt()

        hostField = EditText(this).apply {
            hint = "IP or Bluetooth address"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setText(prefs.getString("host", ""))
            setOnEditorActionListener { _, _, _ -> toggleConnection(); true }
        }
        pinField = EditText(this).apply {
            hint = "PIN"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setText(prefs.getString("pin", ""))
        }
        scanBtn = Button(this).apply { text = "Scan"; setOnClickListener { scan() } }
        connectBtn = Button(this).apply { text = "Connect"; setOnClickListener { toggleConnection() } }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, 0, pad, 0)
            addView(hostField, LinearLayout.LayoutParams(0, -2, 1f))
            addView(pinField, LinearLayout.LayoutParams(dp(72f).toInt(), -2))
            addView(scanBtn)
            addView(connectBtn)
        }
        status = TextView(this).apply {
            setPadding(pad * 2, 0, pad * 2, pad / 2)
            setTextColor(Color.GRAY)
            text = "Tap Scan to find the CardputerZero over Wi-Fi or Bluetooth."
        }
        screen = ScreenView(this)
        keyboard = KeyboardView(this, ::sendKey, ::toggleIme)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            addView(bar)
            addView(status)
            addView(screen, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(keyboard, LinearLayout.LayoutParams(-1, -2))
        }
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 30) {
            // Draw edge-to-edge everywhere and pad for system bars and the soft keyboard ourselves.
            @Suppress("DEPRECATION")
            window.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { v, insets ->
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(i.left, i.top, i.right, i.bottom)
                insets
            }
        }
        @Suppress("DEPRECATION")
        window.statusBarColor = bg
        @Suppress("DEPRECATION")
        window.navigationBarColor = bg
    }

    override fun onDestroy() {
        conn?.close()
        conn = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ connect ---

    private fun toggleConnection() {
        val c = conn
        if (c != null) {
            c.close()
            return
        }
        var target = hostField.text.toString().trim()
        if (target.isEmpty()) {
            status.text = "Enter the device's IP or Bluetooth address, or tap Scan."
            return
        }
        if (Connection.isBluetooth(target.uppercase())) {
            target = target.uppercase()
            if (!withPermissions(btConnectPermissions()) { toggleConnection() }) return
        }
        val pin = pinField.text.toString()
        prefs.edit().putString("host", target).putString("pin", pin).apply()
        hideIme(hostField)
        status.text = "Connecting to $target…"
        connectBtn.text = "Disconnect"
        conn = Connection(target, pin, this, bt).also { it.start() }
    }

    override fun onConnected(c: Connection) {
        ui.post {
            if (c !== conn) return@post
            val via = if (c.viaBluetooth) "Bluetooth" else "Wi-Fi"
            if (c.viaBluetooth) prefs.edit().putString("bt_last", hostField.text.toString().trim().uppercase()).apply()
            status.text = "Connected to ${hostField.text.toString().trim()} via $via"
            screen.placeholder = "Waiting for screen…"
            screen.requestFocus() // so hardware keys go to the device, not the IP field
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onFrame(c: Connection, frame: Bitmap) {
        ui.post {
            if (c === conn) screen.frame = frame
        }
    }

    override fun onClosed(c: Connection, error: String?) {
        ui.post {
            if (c !== conn) return@post
            conn = null
            connectBtn.text = "Connect"
            status.text = if (error != null) "Disconnected: $error" else "Disconnected"
            screen.frame = null
            screen.placeholder = "Not connected"
            keyboard.reset()
            wrappedInShift.clear()
            hwShiftDown = false
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // --------------------------------------------------------- permissions ---

    private fun btConnectPermissions() =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()

    private fun btScanPermissions() = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun granted(perms: Array<String>) =
        perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Runs [then] now if [perms] are granted (returns true); otherwise asks, runs it afterwards, returns false. */
    private fun withPermissions(perms: Array<String>, then: () -> Unit): Boolean {
        if (granted(perms)) return true
        afterPermissions = then
        requestPermissions(perms, 1)
        return false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        val then = afterPermissions ?: return
        afterPermissions = null
        then()
    }

    // ---------------------------------------------------------------- scan ---

    private var scanning = false

    private fun scan() {
        if (scanning) return
        val adapter = bt
        val useBt = adapter != null && adapter.isEnabled
        if (useBt && !granted(btScanPermissions()) && afterPermissions == null) {
            // Ask once; if refused, scan() runs again and falls back to Wi-Fi only.
            afterPermissions = { scanWith(granted(btScanPermissions())) }
            requestPermissions(btScanPermissions(), 1)
            return
        }
        scanWith(useBt && granted(btScanPermissions()))
    }

    @SuppressLint("MissingPermission") // checked by scan()
    private fun scanWith(useBt: Boolean) {
        scanning = true
        scanBtn.isEnabled = false
        status.text = if (useBt) "Scanning Wi-Fi and Bluetooth…" else "Scanning Wi-Fi…" +
            if (bt?.isEnabled == false) " (Bluetooth is off)" else ""
        val found = LinkedHashMap<String, Discovery.Device>()
        // Bluetooth scans only reach the CardputerZero from very close, so always offer the last one used.
        val lastBt = prefs.getString("bt_last", null)
        if (lastBt != null && bt != null) {
            found[lastBt] = Discovery.Device(prefs.getString("bt_last_name", null) ?: "Last used", lastBt, bluetooth = true)
        }

        thread(isDaemon = true) {
            val wifi = try {
                Discovery.scan()
            } catch (e: Exception) {
                emptyList()
            }
            ui.post {
                wifi.forEach { found.putIfAbsent(it.address, it) }
                // The device also tells us its Bluetooth address over Wi-Fi.
                wifi.forEach { d ->
                    val mac = d.btAddress ?: return@forEach
                    found[mac] = Discovery.Device(d.name, mac, bluetooth = true)
                }
            }
        }

        var receiver: BroadcastReceiver? = null
        var btStarted = false
        var btSeen = 0
        val adapter = bt
        var finished = false

        fun finish() {
            if (finished) return
            finished = true
            receiver?.let {
                unregisterReceiver(it)
                adapter?.cancelDiscovery()
            }
            scanning = false
            scanBtn.isEnabled = true
            val list = found.values.toList()
            Log.i(TAG, "scan done: ${list.size} match(es), bluetooth started=$btStarted, saw $btSeen device(s)")
            when (list.size) {
                0 -> status.text = "No device found. Is cpz-remote running, and is Wi-Fi or Bluetooth on?"
                1 -> pick(list[0])
                else -> AlertDialog.Builder(this)
                    .setTitle("Choose a device")
                    .setItems(list.map(::label).toTypedArray()) { _, i -> pick(list[i]) }
                    .show()
            }
            if (useBt && !btStarted) status.append("\nBluetooth scan could not start.")
        }

        if (useBt && adapter != null) {
            fun add(d: BluetoothDevice?, name: String?) {
                if (d == null) return
                val n = name ?: d.name
                Log.d(TAG, "bluetooth saw ${d.address} name=$n")
                if (n != null && n.contains("cardputer", ignoreCase = true)) {
                    found.putIfAbsent(d.address, Discovery.Device(n, d.address, bluetooth = true))
                }
            }
            adapter.bondedDevices?.forEach { add(it, it.name) }
            receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, i: Intent) {
                    if (i.action == BluetoothAdapter.ACTION_DISCOVERY_FINISHED) {
                        // Let the Wi-Fi scan (1.5 s) finish if Bluetooth was very quick.
                        ui.postDelayed(::finish, 500)
                        return
                    }
                    val d = if (Build.VERSION.SDK_INT >= 33) {
                        i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    btSeen++
                    add(d, i.getStringExtra(BluetoothDevice.EXTRA_NAME))
                }
            }
            val filter = IntentFilter(BluetoothDevice.ACTION_FOUND).apply {
                addAction(BluetoothDevice.ACTION_NAME_CHANGED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_EXPORTED)
            else registerReceiver(receiver, filter)
            adapter.cancelDiscovery()
            btStarted = adapter.startDiscovery()
            Log.i(TAG, "bluetooth discovery started=$btStarted")
        }

        // Classic Bluetooth discovery takes about 12 s; Wi-Fi alone takes 1.5 s.
        ui.postDelayed(::finish, if (useBt && btStarted) 15000L else 1700L)
    }

    private fun label(d: Discovery.Device) =
        "${d.name}  ·  ${if (d.bluetooth) "Bluetooth" else "Wi-Fi"}  ${d.address}"

    private fun pick(d: Discovery.Device) {
        hostField.setText(d.address)
        if (d.bluetooth) prefs.edit().putString("bt_last_name", d.name).apply()
        status.text = "Found ${label(d)}. Tap Connect."
    }

    // --------------------------------------------------------------- keys ---

    private fun sendKey(code: Int, down: Boolean) {
        conn?.sendKey(code, down)
    }

    private fun tap(k: LKey) {
        if (k.shift) sendKey(L.LEFTSHIFT, true)
        sendKey(k.code, true)
        sendKey(k.code, false)
        if (k.shift) sendKey(L.LEFTSHIFT, false)
    }

    /** Phone soft keyboard and Bluetooth/USB keyboards both arrive here. */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (conn == null || currentFocus is EditText) return super.dispatchKeyEvent(e)

        @Suppress("DEPRECATION")
        if (e.action == KeyEvent.ACTION_MULTIPLE && e.keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            e.characters?.forEach { ch -> KeyMap.fromChar(ch)?.let(::tap) }
            return true
        }
        val k = KeyMap.fromAndroid(e.keyCode) ?: return super.dispatchKeyEvent(e)
        when (e.action) {
            KeyEvent.ACTION_DOWN -> {
                if (KeyMap.isShift(e.keyCode)) hwShiftDown = true
                if (e.repeatCount == 0) {
                    // The device repeats held keys itself, so only the first press is sent.
                    val needShift = !KeyMap.isModifier(e.keyCode) && !hwShiftDown && (k.shift || e.isShiftPressed)
                    if (needShift) {
                        sendKey(L.LEFTSHIFT, true)
                        wrappedInShift.add(e.keyCode)
                    }
                    sendKey(k.code, true)
                }
            }
            KeyEvent.ACTION_UP -> {
                sendKey(k.code, false)
                if (wrappedInShift.remove(e.keyCode)) sendKey(L.LEFTSHIFT, false)
                if (KeyMap.isShift(e.keyCode)) hwShiftDown = false
            }
        }
        return true
    }

    private fun toggleIme() {
        val imm = getSystemService(InputMethodManager::class.java)
        val shown = if (Build.VERSION.SDK_INT >= 30) {
            window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        } else {
            imeShownFallback
        }
        if (shown) {
            imm.hideSoftInputFromWindow(screen.windowToken, 0)
        } else {
            screen.requestFocus()
            imm.showSoftInput(screen, 0)
        }
        imeShownFallback = !shown
    }

    private fun hideIme(v: View) {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0)
        v.clearFocus()
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
