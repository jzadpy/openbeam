package app.jzad.openbeam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.DynamicColors
import app.jzad.openbeam.databinding.ActivityMainBinding
import app.jzad.openbeam.nearby.NearbyTransferManager
import app.jzad.openbeam.storage.BeamFileStore
import app.jzad.openbeam.storage.PickedMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var nearby: NearbyTransferManager

    private var selectedMedia: PickedMedia? = null
    private var currentMode: NearbyTransferManager.Mode = NearbyTransferManager.Mode.IDLE
    private var nfcAdapter: NfcAdapter? = null
    private var pendingAction: (() -> Unit)? = null

    private var isNearbyConnected = false

    private val pickMedia = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) {
            log(getString(R.string.status_no_selection))
            stopEverything()
            return@registerForActivityResult
        }

        processPickedUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        val token = OpenBeamPrefs.getOrCreateSessionToken(this)
        binding.tokenText.text = getString(R.string.token_nfc_placeholder, token)

        nearby = NearbyTransferManager(
            context = this,
            status = ::updateStatus,
            onConnected = { endpoint ->
                runOnUiThread {
                    isNearbyConnected = true
                    Toast.makeText(this, getString(R.string.connected_with, endpoint), Toast.LENGTH_SHORT).show()
                    
                    // Auto-send if in sender mode and media is ready
                    if (currentMode == NearbyTransferManager.Mode.SENDER) {
                        val picked = selectedMedia
                        if (picked != null) {
                            binding.transferProgress.apply {
                                visibility = View.VISIBLE
                                isIndeterminate = true
                            }
                            nearby.sendMedia(picked)
                        }
                    }
                }
            },
            onDisconnected = {
                runOnUiThread {
                    isNearbyConnected = false
                }
            },
            onProgress = { progress ->
                runOnUiThread {
                    binding.transferProgress.apply {
                        visibility = View.VISIBLE
                        isIndeterminate = false
                        setProgress((progress * 100).toInt(), true)
                    }
                }
            },
            onReceived = { fileName ->
                runOnUiThread {
                    binding.transferProgress.visibility = View.GONE
                    Toast.makeText(this, getString(R.string.file_saved, fileName), Toast.LENGTH_LONG).show()
                }
            },
        )

        binding.senderButton.setOnClickListener {
            ensureNearbyPermissionsAndThen {
                currentMode = NearbyTransferManager.Mode.SENDER
                // Step 1: Pick file
                pickMedia.launch("*/*")
                updateStatus(getString(R.string.status_sender_active))
            }
        }

        binding.receiverButton.setOnClickListener {
            ensureNearbyPermissionsAndThen {
                currentMode = NearbyTransferManager.Mode.RECEIVER
                nearby.startReceiver()
                enableNfcReader()
                OpenBeamPrefs.setTileReady(context = this, ready = true)
                OpenBeamTileService.refresh(this)
                updateStatus(getString(R.string.status_receiver_active))
            }
        }

        binding.stopButton.setOnClickListener {
            stopEverything()
        }

        handleLaunchIntent(intent)
        updateStatusForMode()
        requestNeededPermissionsIfMissing()
    }

    override fun onResume() {
        super.onResume()
        if (currentMode == NearbyTransferManager.Mode.RECEIVER) {
            enableNfcReader()
        }
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent) {
        val fromTile = intent.getBooleanExtra(EXTRA_FROM_TILE, false)
        val tileReady = intent.getBooleanExtra(EXTRA_TILE_READY, false)

        if (fromTile) {
            if (tileReady) {
                ensureNearbyPermissionsAndThen {
                    currentMode = NearbyTransferManager.Mode.RECEIVER
                    nearby.startReceiver()
                    enableNfcReader()
                    updateStatus(getString(R.string.status_receiver_active))
                }
            } else {
                stopEverything()
            }
        } else if (intent.action == Intent.ACTION_SEND) {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (uri != null) {
                ensureNearbyPermissionsAndThen {
                    currentMode = NearbyTransferManager.Mode.SENDER
                    processPickedUri(uri)
                    updateStatus(getString(R.string.status_sender_active))
                }
            }
        }
    }

    private fun processPickedUri(uri: android.net.Uri) {
        lifecycleScope.launch {
            val picked = withContext(Dispatchers.IO) {
                BeamFileStore.copyUriToCache(this@MainActivity, uri)
            }
            if (picked != null) {
                selectedMedia = picked
                log(getString(R.string.status_media_ready, picked.displayName, formatBytes(picked.size)))
                
                if (currentMode == NearbyTransferManager.Mode.SENDER) {
                    nearby.startSender(localDisplayName())
                    OpenBeamPrefs.setTileReady(context = this@MainActivity, ready = true)
                    OpenBeamTileService.refresh(this@MainActivity)
                }
            } else {
                log(getString(R.string.error_copying_photo))
                stopEverything()
            }
        }
    }

    private fun enableNfcReader() {
        val adapter = nfcAdapter
        if ((adapter == null) || !adapter.isEnabled) {
            log(getString(R.string.status_nfc_off))
            return
        }
        adapter.enableReaderMode(
            this,
            { tag -> handleTag(tag) },
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            null,
        )
    }

    private fun handleTag(tag: Tag) {
        lifecycleScope.launch(Dispatchers.IO) {
            val isoDep = IsoDep.get(tag)
            if (isoDep == null) {
                withContext(Dispatchers.Main) { log(getString(R.string.status_nfc_error)) }
                return@launch
            }

            runCatching {
                isoDep.connect()
                isoDep.timeout = 5000
                val selectApdu = hexToBytes("00A4040008F001020304050607")
                val getTokenApdu = hexToBytes("80CA000000")

                withContext(Dispatchers.Main) { log(getString(R.string.status_nfc_reading)) }
                val selectResponse = isoDep.transceive(selectApdu)
                if (!isSuccess(selectResponse)) {
                    return@runCatching
                }

                withContext(Dispatchers.Main) { log(getString(R.string.status_nfc_token)) }
                val tokenResponse = isoDep.transceive(getTokenApdu)
                val token = parseResponseText(tokenResponse)

                withContext(Dispatchers.Main) {
                    binding.tokenText.text = getString(R.string.token_nfc_placeholder, token)
                    log(getString(R.string.status_nfc_success, token))
                    nearby.beginDiscoveryAfterNfc()
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    val msg = e.message ?: getString(R.string.idle)
                    log(getString(R.string.error_nfc_msg, msg))
                    Toast.makeText(this@MainActivity, getString(R.string.status_nfc_error) + ": $msg", Toast.LENGTH_SHORT).show()
                }
            }.also {
                runCatching { isoDep.close() }
            }
        }
    }

    private fun parseResponseText(response: ByteArray): String {
        if (response.size < 2) return getString(R.string.nfc_empty)
        if (!isSuccess(response)) return getString(R.string.nfc_error)
        return response.dropLast(2).toByteArray().toString(Charsets.UTF_8)
    }

    private fun isSuccess(response: ByteArray): Boolean {
        return (response.size >= 2) &&
            (response[response.size - 2] == 0x90.toByte()) &&
            (response.last() == 0x00.toByte())
    }

    private fun stopEverything() {
        nearby.stopAll()
        nfcAdapter?.disableReaderMode(this)
        currentMode = NearbyTransferManager.Mode.IDLE
        selectedMedia = null
        isNearbyConnected = false
        binding.transferProgress.visibility = View.GONE
        OpenBeamPrefs.setTileReady(context = this, ready = false)
        OpenBeamTileService.refresh(this)
        updateStatusForMode()
    }

    private fun updateStatusForMode() {
        binding.statusText.text = when (currentMode) {
            NearbyTransferManager.Mode.IDLE -> getString(R.string.status_ready)
            NearbyTransferManager.Mode.SENDER -> getString(R.string.status_sender_active)
            NearbyTransferManager.Mode.RECEIVER -> getString(R.string.status_receiver_active)
        }
    }

    private fun requestNeededPermissionsIfMissing() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_PERMS)
        }
    }

    private fun ensureNearbyPermissionsAndThen(block: () -> Unit) {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            block()
        } else {
            pendingAction = block
            requestPermissions(missing.toTypedArray(), REQ_PERMS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return

        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            pendingAction?.invoke()
            pendingAction = null
            log(getString(R.string.status_perm_ready))
        } else {
            log(getString(R.string.status_perm_missing))
        }
    }

    private fun requiredPermissions(): List<String> {
        val list = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.BLUETOOTH_SCAN
            list += Manifest.permission.BLUETOOTH_CONNECT
            list += Manifest.permission.BLUETOOTH_ADVERTISE
            list += Manifest.permission.NEARBY_WIFI_DEVICES
            list += Manifest.permission.ACCESS_FINE_LOCATION
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += Manifest.permission.BLUETOOTH_SCAN
            list += Manifest.permission.BLUETOOTH_CONNECT
            list += Manifest.permission.BLUETOOTH_ADVERTISE
            list += Manifest.permission.ACCESS_FINE_LOCATION
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            list += Manifest.permission.ACCESS_FINE_LOCATION
        } else {
            list += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        
        // Include storage for Pre-Q devices
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            list += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }

        return list.distinct()
    }

    private fun localDisplayName(): String {
        return "OpenBeam-${Build.MODEL.take(8).uppercase(Locale.getDefault())}"
    }

    private fun formatBytes(bytes: Long): String {
        val kb = bytes / 1024.0
        return if (kb < 1024) String.format(Locale.getDefault(), "%.1f KB", kb)
        else String.format(Locale.getDefault(), "%.1f MB", kb / 1024.0)
    }

    private fun updateStatus(message: String) {
        runOnUiThread {
            binding.statusText.text = message
            binding.logText.text = message
        }
    }

    private fun log(message: String) {
        binding.logText.text = message
    }

    companion object {
        const val EXTRA_FROM_TILE = "extra_from_tile"
        const val EXTRA_TILE_READY = "extra_tile_ready"
        private const val REQ_PERMS = 2201
    }
}

private fun hexToBytes(hex: String): ByteArray {
    require((hex.length % 2) == 0) { "HEX inválido" }
    return ByteArray(hex.length / 2) { index ->
        val start = index * 2
        hex.substring(start, start + 2).toInt(16).toByte()
    }
}
