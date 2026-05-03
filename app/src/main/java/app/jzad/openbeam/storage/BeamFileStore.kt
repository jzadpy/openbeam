package app.jzad.openbeam.storage

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

data class PickedMedia(
    val file: File,
    val displayName: String,
    val mimeType: String,
    val size: Long,
)

object BeamFileStore {

    fun copyUriToCache(context: Context, uri: Uri): PickedMedia? {
        val resolver = context.contentResolver
        val displayName = queryDisplayName(resolver, uri) ?: "file_${System.currentTimeMillis()}"
        val mimeType = resolver.getType(uri) ?: "application/octet-stream"
        val tempFile = File(context.cacheDir, "send_${System.currentTimeMillis()}_$displayName")

        return try {
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            val size = tempFile.length()
            PickedMedia(tempFile, displayName, mimeType, size)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Copies a file from a Nearby URI to the public Downloads/OpenBeam folder.
     */
    fun saveIncomingToDownloads(context: Context, uri: Uri, fileName: String, mimeType: String): Uri? {
        val resolver = context.contentResolver
        val safeName = fileName.ifBlank { "received_${System.currentTimeMillis()}" }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OpenBeam")
                }
                val targetUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (targetUri != null) {
                    resolver.openInputStream(uri)?.use { input ->
                        resolver.openOutputStream(targetUri)?.use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                targetUri
            } else {
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val openBeamDir = File(downloadDir, "OpenBeam")
                if (!openBeamDir.exists()) openBeamDir.mkdirs()
                
                val outFile = File(openBeamDir, safeName)
                resolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Uri.fromFile(outFile)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
        }
    }
}
