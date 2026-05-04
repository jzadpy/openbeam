package app.jzad.openbeam.nearby

import android.content.Context
import android.os.Build
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import app.jzad.openbeam.R
import app.jzad.openbeam.storage.BeamFileStore
import app.jzad.openbeam.storage.PickedMedia
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class NearbyTransferManager(
    context: Context,
    private val status: (String) -> Unit,
    private val onConnected: (String) -> Unit,
    private val onDisconnected: () -> Unit = {},
    private val onProgress: (Float) -> Unit = {},
    private val onReceived: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(appContext)
    private val strategy = Strategy.P2P_STAR

    private var mode: Mode = Mode.IDLE
    private var connectedEndpointId: String? = null
    private var discoveredOnce = false

    private var lastIncomingMeta: FileMeta? = null
    private val filePayloads = ConcurrentHashMap<Long, Payload.File>()

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            status(appContext.getString(R.string.nearby_connection_initiated, connectionInfo.endpointName))
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: com.google.android.gms.nearby.connection.ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedEndpointId = endpointId
                onConnected(result.status.statusMessage ?: endpointId)
                status(appContext.getString(R.string.nearby_connected))
            } else {
                status(appContext.getString(R.string.nearby_connection_failed, result.status.statusCode))
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (connectedEndpointId == endpointId) {
                connectedEndpointId = null
                onDisconnected()
            }
            status(appContext.getString(R.string.nearby_disconnected))
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, discoveredEndpointInfo: DiscoveredEndpointInfo) {
            if (mode != Mode.RECEIVER) return
            if (discoveredOnce) return
            discoveredOnce = true
            status(appContext.getString(R.string.nearby_endpoint_found, discoveredEndpointInfo.endpointName))
            connectionsClient.requestConnection(
                localName(),
                endpointId,
                connectionLifecycleCallback
            ).addOnFailureListener {
                discoveredOnce = false
            }
        }

        override fun onEndpointLost(endpointId: String) {
            status(appContext.getString(R.string.nearby_endpoint_lost))
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val bytes = payload.asBytes() ?: return
                    val text = String(bytes, Charsets.UTF_8)
                    val meta = runCatching {
                        val json = JSONObject(text)
                        FileMeta(
                            fileName = json.optString("fileName", "received_file"),
                            mimeType = json.optString("mimeType", "application/octet-stream"),
                            size = json.optLong("size", -1L)
                        )
                    }.getOrNull()

                    if (meta != null) {
                        lastIncomingMeta = meta
                        status(appContext.getString(R.string.nearby_meta_received, meta.fileName))
                    } else {
                        status(appContext.getString(R.string.nearby_text_received, text))
                        onReceived(text)
                    }
                }

                Payload.Type.FILE -> {
                    val filePayload = payload.asFile() ?: return
                    filePayloads[payload.id] = filePayload
                    status(appContext.getString(R.string.nearby_file_received))
                }

                Payload.Type.STREAM -> status(appContext.getString(R.string.nearby_stream_received))
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    val progress = update.bytesTransferred.toFloat() / update.totalBytes.toFloat()
                    onProgress(progress)
                }
                PayloadTransferUpdate.Status.SUCCESS -> {
                    onProgress(1f)
                    val filePayload = filePayloads.remove(update.payloadId)
                    val uri = filePayload?.asUri()
                    if (uri != null) {
                        val safeName = lastIncomingMeta?.fileName ?: "received_file"
                        val mimeType = lastIncomingMeta?.mimeType ?: "application/octet-stream"
                        val savedUri = BeamFileStore.saveIncomingToDownloads(
                            appContext,
                            uri,
                            safeName,
                            mimeType
                        )
                        if (savedUri != null) {
                            status(appContext.getString(R.string.nearby_file_saved, safeName))
                            onReceived(safeName)
                        } else {
                            status(appContext.getString(R.string.nearby_save_error))
                        }
                    }
                }
                PayloadTransferUpdate.Status.FAILURE -> {
                    onProgress(0f)
                    status(appContext.getString(R.string.nearby_transfer_failed))
                }
            }
        }
    }

    fun startSender(displayName: String) {
        mode = Mode.SENDER
        discoveredOnce = false
        status(appContext.getString(R.string.status_sender_active))
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()

        connectionsClient.startAdvertising(
            displayName,
            SERVICE_ID,
            connectionLifecycleCallback,
            AdvertisingOptions.Builder().setStrategy(strategy).build()
        )
    }

    fun startReceiver() {
        mode = Mode.RECEIVER
        discoveredOnce = false
        status(appContext.getString(R.string.nearby_wait_nfc))
        connectionsClient.stopAdvertising()
        connectionsClient.stopAllEndpoints()
    }

    fun beginDiscoveryAfterNfc() {
        if (mode != Mode.RECEIVER) return
        status(appContext.getString(R.string.nearby_searching))
        
        connectionsClient.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            DiscoveryOptions.Builder().setStrategy(strategy).build()
        )
    }

    fun stopAll() {
        mode = Mode.IDLE
        discoveredOnce = false
        connectedEndpointId = null
        lastIncomingMeta = null
        filePayloads.clear()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        status(appContext.getString(R.string.nearby_stopped))
    }

    fun sendMedia(media: PickedMedia) {
        val endpoint = connectedEndpointId
        if (endpoint == null) {
            status(appContext.getString(R.string.nearby_no_connection))
            return
        }

        val metaJson = JSONObject().apply {
            put("fileName", media.displayName)
            put("mimeType", media.mimeType)
            put("size", media.size)
        }.toString()

        connectionsClient.sendPayload(endpoint, Payload.fromBytes(metaJson.toByteArray(Charsets.UTF_8)))
        connectionsClient.sendPayload(endpoint, Payload.fromFile(media.file))
        status(appContext.getString(R.string.nearby_sending, media.displayName))
    }

    private fun localName(): String = "OpenBeam-${Build.MODEL.take(8)}"

    enum class Mode { IDLE, SENDER, RECEIVER }

    data class FileMeta(
        val fileName: String,
        val mimeType: String,
        val size: Long
    )

    companion object {
        const val SERVICE_ID = "app.jzad.openbeam.nearby.OFFLINE_TRANSFER"
    }
}
