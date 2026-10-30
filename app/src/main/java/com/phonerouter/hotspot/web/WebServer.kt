package com.phonerouter.hotspot.web

import android.content.Context
import com.phonerouter.hotspot.Prefs
import com.phonerouter.hotspot.hotspot.HotspotManager
import com.phonerouter.hotspot.hotspot.NetUtils
import com.phonerouter.hotspot.hotspot.Schedule
import com.phonerouter.hotspot.hotspot.ScheduleManager
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Embedded management server. Serves the static web UI from assets/web and a JSON API
 * guarded by a token from /api/login. Restricted strictly to loopback and hotspot LAN clients.
 */
class WebServer(
    private val context: Context,
    private val manager: HotspotManager,
    port: Int,
    private val password: String,
) : NanoHTTPD(port) {

    private val tokens = ConcurrentHashMap<String, Long>()
    private val loginFailures = ConcurrentHashMap<String, FailureRecord>()
    private val rng = SecureRandom()
    private val schedules = ScheduleManager(context)

    private data class FailureRecord(val count: Int, val windowStartMs: Long, val lockedUntilMs: Long)

    override fun serve(session: IHTTPSession): Response {
        return try {
            val remoteIp = session.remoteIpAddress ?: ""
            if (!isAllowedRemoteIp(remoteIp)) {
                return json(
                    Response.Status.FORBIDDEN,
                    JSONObject().put("ok", false).put("error", "forbidden network interface")
                )
            }
            val uri = session.uri
            when {
                uri.startsWith("/api/") -> handleApi(session, uri, remoteIp)
                else -> serveStatic(uri)
            }
        } catch (t: Throwable) {
            json(Response.Status.INTERNAL_ERROR, JSONObject().put("ok", false).put("error", "internal error"))
        }
    }

    /**
     * Only allow requests from loopback or the active Wi-Fi hotspot subnet.
     * Rejects requests arriving over mobile data (rmnet/ccmni) or upstream WAN interfaces.
     */
    private fun isAllowedRemoteIp(ip: String): Boolean {
        if (ip == "127.0.0.1" || ip == "::1" || ip == "0:0:0:0:0:0:0:1") return true
        val gatewayPrefix = Prefs.GATEWAY.substringBeforeLast('.') + "."
        if (ip.startsWith(gatewayPrefix)) return true
        val livePrefixes = NetUtils.apIPv4Prefixes(NetUtils.apInterface())
        return livePrefixes.any { ip.startsWith(it) }
    }

    // ---- API ----------------------------------------------------------------

    private fun handleApi(session: IHTTPSession, uri: String, remoteIp: String): Response {
        if (uri == "/api/login" && session.method == Method.POST) {
            val now = System.currentTimeMillis()
            val record = loginFailures[remoteIp]
            if (record != null && now < record.lockedUntilMs) {
                return json(
                    Response.Status.FORBIDDEN,
                    JSONObject().put("ok", false).put("error", "尝试次数过多，请稍后再试")
                )
            }
            val pw = readJson(session).optString("password")
            return if (constantTimeEquals(pw, password)) {
                loginFailures.remove(remoteIp)
                val token = issueToken(now)
                json(Response.Status.OK, JSONObject().put("ok", true).put("token", token))
            } else {
                recordFailure(remoteIp, now)
                json(Response.Status.FORBIDDEN, JSONObject().put("ok", false).put("error", "密码错误"))
            }
        }

        if (!authorized(session)) {
            return json(Response.Status.UNAUTHORIZED, JSONObject().put("ok", false).put("error", "未授权"))
        }

        val get = session.method == Method.GET
        val post = session.method == Method.POST
        return when {
            uri == "/api/status" && get -> json(Response.Status.OK, manager.status())

            // hotspot config
            uri == "/api/config" && get ->
                json(Response.Status.OK, JSONObject().put("ok", true).put("config", manager.readConfig().toJson()))
            uri == "/api/config" && post -> {
                val b = readJson(session)
                json(Response.Status.OK, manager.applyConfig(
                    ssid = b.optStringOrNull("ssid"),
                    passphrase = b.optStringOrNull("passphrase"),
                    security = b.optIntOrNull("security"),
                    band = b.optIntOrNull("band"),
                    hidden = if (b.has("hidden")) b.optBoolean("hidden") else null,
                    maxClients = b.optIntOrNull("maxClients"),
                ))
            }
            uri == "/api/hotspot" && post -> {
                val enabled = readJson(session).optBoolean("enabled", false)
                json(Response.Status.OK, JSONObject().put("ok", manager.setHotspot(enabled)).put("active", manager.isActive()))
            }
            uri == "/api/reboot-hotspot" && post ->
                json(Response.Status.OK, JSONObject().put("ok", manager.rebootHotspot()))

            // clients
            uri == "/api/clients" && get -> json(Response.Status.OK, manager.clientsJson().put("ok", true))
            uri == "/api/clients/block" && post -> {
                val b = readJson(session)
                json(Response.Status.OK, manager.blockClient(b.optString("mac"), b.optBoolean("block", true)))
            }

            // data balance
            uri == "/api/data-usage" && get -> json(Response.Status.OK, manager.dataUsageJson())
            uri == "/api/data-usage/refresh" && post -> json(Response.Status.OK, manager.refreshDataUsage())
            uri == "/api/data-usage/config" && post -> {
                val b = readJson(session)
                json(Response.Status.OK, manager.setDataPlan(b.optLong("quotaMb", 0), b.optInt("resetDay", 1)))
            }

            // speed limit
            uri == "/api/speed-limit" && get -> json(Response.Status.OK, manager.speedLimitJson())
            uri == "/api/speed-limit" && post -> {
                val b = readJson(session)
                json(Response.Status.OK, manager.setSpeedLimit(b.optInt("downKbps", 0), b.optInt("upKbps", 0)))
            }

            // schedules
            uri == "/api/schedules" && get ->
                json(Response.Status.OK, JSONObject().put("ok", true).put("schedules", schedules.toJsonArray()))
            uri == "/api/schedules" && post -> {
                val b = readJson(session)
                val days = mutableListOf<Int>()
                b.optJSONArray("days")?.let { for (i in 0 until it.length()) days.add(it.getInt(i)) }
                val s = schedules.add(
                    Schedule(
                        id = b.optLong("id", 0),
                        hour = b.optInt("hour", 0),
                        minute = b.optInt("minute", 0),
                        days = days,
                        action = b.optString("action", "on"),
                        enabled = b.optBoolean("enabled", true),
                    )
                )
                json(Response.Status.OK, JSONObject().put("ok", true).put("schedule", s.toJson()))
            }
            uri == "/api/schedules/delete" && post -> {
                schedules.remove(readJson(session).optLong("id", 0)); okResp()
            }
            uri == "/api/schedules/toggle" && post -> {
                val b = readJson(session)
                schedules.setEnabled(b.optLong("id", 0), b.optBoolean("enabled", true)); okResp()
            }

            // SMS & File endpoints are disabled for security
            uri.startsWith("/api/sms") || uri.startsWith("/api/files") ->
                json(Response.Status.FORBIDDEN, JSONObject().put("ok", false).put("error", "disabled for security"))

            else -> json(Response.Status.NOT_FOUND, JSONObject().put("ok", false).put("error", "not found"))
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (b.isBlank()) return false
        return MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }

    private fun recordFailure(ip: String, now: Long) {
        val current = loginFailures[ip]
        if (current == null || now - current.windowStartMs > LOGIN_WINDOW_MS) {
            loginFailures[ip] = FailureRecord(1, now, 0L)
        } else {
            val nextCount = current.count + 1
            val lockUntil = if (nextCount >= MAX_LOGIN_ATTEMPTS) now + LOCKOUT_DURATION_MS else 0L
            loginFailures[ip] = FailureRecord(nextCount, current.windowStartMs, lockUntil)
        }
    }

    private fun issueToken(now: Long): String {
        pruneTokens(now)
        if (tokens.size >= MAX_TOKENS) {
            val oldest = tokens.entries.minByOrNull { it.value }?.key
            if (oldest != null) tokens.remove(oldest)
        }
        val bytes = ByteArray(24)
        rng.nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        tokens[token] = now + TOKEN_TTL_MS
        return token
    }

    private fun pruneTokens(now: Long) {
        val it = tokens.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value < now) it.remove()
        }
    }

    private fun authorized(session: IHTTPSession): Boolean {
        val now = System.currentTimeMillis()
        pruneTokens(now)
        session.headers["x-auth"]?.let { if (tokens.containsKey(it)) return true }
        session.headers["cookie"]?.let { cookie ->
            val tok = cookie.split(";").map { it.trim() }
                .firstOrNull { it.startsWith("pr_token=") }?.substringAfter("pr_token=")
            if (tok != null && tokens.containsKey(tok)) return true
        }
        return false
    }

    // ---- static --------------------------------------------------------------

    private fun serveStatic(uri: String): Response {
        val cleanUri = uri.substringBefore('?').substringBefore('#')
        if (cleanUri.contains("..") || cleanUri.contains('\\') || cleanUri.contains('\u0000')) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "403")
        }
        val path = if (cleanUri == "/" || cleanUri.isEmpty()) "web/index.html" else "web/${cleanUri.trimStart('/')}"
        return try {
            val bytes = context.assets.open(path).use { it.readBytes() }
            newFixedLengthResponse(Response.Status.OK, mime(path), bytes.inputStream(), bytes.size.toLong())
        } catch (t: Throwable) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404")
        }
    }

    private fun mime(path: String): String = when {
        path.endsWith(".html") -> "text/html; charset=utf-8"
        path.endsWith(".js") -> "application/javascript; charset=utf-8"
        path.endsWith(".css") -> "text/css; charset=utf-8"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".png") -> "image/png"
        path.endsWith(".ico") -> "image/x-icon"
        else -> "application/octet-stream"
    }

    // ---- helpers --------------------------------------------------------------

    private fun readJson(session: IHTTPSession): JSONObject {
        return try {
            val map = HashMap<String, String>()
            session.parseBody(map)
            val raw = map["postData"] ?: session.queryParameterString
            if (raw.isNullOrBlank()) JSONObject() else JSONObject(raw)
        } catch (t: Throwable) {
            JSONObject()
        }
    }

    private fun json(status: Response.Status, obj: JSONObject): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", obj.toString())

    private fun okResp(): Response = json(Response.Status.OK, JSONObject().put("ok", true))

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    companion object {
        private const val MAX_LOGIN_ATTEMPTS = 5
        private const val LOGIN_WINDOW_MS = 60_000L
        private const val LOCKOUT_DURATION_MS = 300_000L
        private const val TOKEN_TTL_MS = 12 * 3600_000L
        private const val MAX_TOKENS = 16
    }
}
