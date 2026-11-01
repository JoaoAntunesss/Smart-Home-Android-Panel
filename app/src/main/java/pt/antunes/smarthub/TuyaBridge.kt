package pt.antunes.smarthub

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Fala com a Tuya Cloud (Central Europe). As chaves ficam só no armazenamento privado da app. */
class TuyaBridge(ctx: Context, private val web: WebView) {
    private val prefs = ctx.getSharedPreferences("tuya", Context.MODE_PRIVATE)
    private val base = "https://openapi.tuyaeu.com"
    @Volatile private var token: String? = null
    @Volatile private var tokenExp = 0L

    @JavascriptInterface
    fun getConfig(): String = JSONObject()
        .put("id", prefs.getString("id", ""))
        .put("uid", prefs.getString("uid", ""))
        .put("hasSecret", !prefs.getString("secret", "").isNullOrEmpty())
        .toString()

    @JavascriptInterface
    fun saveConfig(id: String, secret: String, uid: String) {
        val e = prefs.edit().putString("id", id).putString("uid", uid)
        if (secret.isNotEmpty()) e.putString("secret", secret)
        e.apply()
        token = null
    }

    /** Assíncrono: devolve o resultado a window.__tuyaCb(cb, textoJson). */
    @JavascriptInterface
    fun request(cb: String, method: String, path: String, body: String) {
        Thread {
            val out = try {
                if (!path.startsWith("/v1.0/")) throw IllegalArgumentException("caminho não permitido")
                call(method, path, body)
            } catch (e: Exception) {
                JSONObject().put("success", false).put("msg", e.message ?: e.javaClass.simpleName).toString()
            }
            web.post { web.evaluateJavascript("window.__tuyaCb(${JSONObject.quote(cb)},${JSONObject.quote(out)})", null) }
        }.start()
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun sha256(s: String) = hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))
    private fun hmac(key: String, msg: String): String {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        return hex(m.doFinal(msg.toByteArray())).uppercase()
    }

    private fun ensureToken() {
        if (token != null && System.currentTimeMillis() < tokenExp - 60_000) return
        val r = JSONObject(raw("GET", "/v1.0/token?grant_type=1", "", false))
        if (!r.optBoolean("success")) throw IllegalStateException(r.optString("msg", "token recusado"))
        val res = r.getJSONObject("result")
        token = res.getString("access_token")
        tokenExp = System.currentTimeMillis() + res.getLong("expire_time") * 1000
    }

    private fun call(method: String, path: String, body: String): String {
        ensureToken()
        return raw(method, path, body, true)
    }

    private fun raw(method: String, path: String, body: String, useToken: Boolean): String {
        val id = prefs.getString("id", "") ?: ""
        val secret = prefs.getString("secret", "") ?: ""
        if (id.isEmpty() || secret.isEmpty()) throw IllegalStateException("faltam o Access ID ou o Secret")
        val t = System.currentTimeMillis().toString()
        val nonce = UUID.randomUUID().toString()
        val access = if (useToken) (token ?: "") else ""
        val toSign = "$method\n${sha256(body)}\n\n$path"
        val sign = hmac(secret, id + access + t + nonce + toSign)
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("client_id", id)
        c.setRequestProperty("sign", sign)
        c.setRequestProperty("t", t)
        c.setRequestProperty("sign_method", "HMAC-SHA256")
        c.setRequestProperty("nonce", nonce)
        c.setRequestProperty("Content-Type", "application/json")
        if (useToken) c.setRequestProperty("access_token", access)
        if (body.isNotEmpty()) {
            c.doOutput = true
            OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(body) }
        }
        val stream = if (c.responseCode in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
        return stream.bufferedReader().use { it.readText() }
    }
}
