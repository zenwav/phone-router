package com.phonerouter.hotspot.feature

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Disabled stub for external storage file operations.
 * Root-backed filesystem access over HTTP is disabled for security.
 */
class FileRepo(@Suppress("UNUSED_PARAMETER") private val context: Context) {

    fun list(@Suppress("UNUSED_PARAMETER") path: String?): JSONObject =
        disabled().put("entries", JSONArray())

    fun mkdir(@Suppress("UNUSED_PARAMETER") path: String?, @Suppress("UNUSED_PARAMETER") name: String): JSONObject =
        disabled()

    fun delete(@Suppress("UNUSED_PARAMETER") path: String?): JSONObject =
        disabled()

    fun rename(@Suppress("UNUSED_PARAMETER") path: String?, @Suppress("UNUSED_PARAMETER") newName: String): JSONObject =
        disabled()

    fun prepareDownload(@Suppress("UNUSED_PARAMETER") path: String?): File? = null

    fun saveUpload(
        @Suppress("UNUSED_PARAMETER") path: String?,
        @Suppress("UNUSED_PARAMETER") name: String,
        tempFile: File
    ): JSONObject {
        runCatching { tempFile.delete() }
        return disabled()
    }

    private fun disabled(): JSONObject =
        JSONObject().put("ok", false).put("error", "File access disabled for security")

    companion object {
        const val ROOT = "/storage/emulated/0"
    }
}
