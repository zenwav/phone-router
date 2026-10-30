package com.phonerouter.hotspot.feature

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Disabled stub for SMS operations.
 * Root-backed SMS reading and privileged SMS sending over HTTP are disabled for security.
 */
class SmsRepo(@Suppress("UNUSED_PARAMETER") private val context: Context) {

    fun subs(): JSONArray = JSONArray()

    fun messages(@Suppress("UNUSED_PARAMETER") box: String, @Suppress("UNUSED_PARAMETER") limit: Int): JSONArray =
        JSONArray()

    fun send(
        @Suppress("UNUSED_PARAMETER") subId: Int,
        @Suppress("UNUSED_PARAMETER") to: String,
        @Suppress("UNUSED_PARAMETER") body: String
    ): JSONObject = JSONObject().put("ok", false).put("error", "SMS access disabled for security")
}
