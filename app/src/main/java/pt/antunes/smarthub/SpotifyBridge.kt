package pt.antunes.smarthub

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/** Spotify Web API com login PKCE (sem segredo). Os tokens ficam só no armazenamento privado da app. */
class SpotifyBridge(private val ctx: Context, private val web: WebView) {
    private val prefs = ctx.getSharedPreferences("spotify", Context.MODE_PRIVATE)
    private val port = 8765
    private val redirect = "http://127.0.0.1:8765/callback"
    @Volatile private var server: java.net.ServerSocket? = null
    private val scopes = "user-read-playback-state user-modify-playback-state user-read-currently-playing"

    private fun js(code: String) { web.post { web.evaluateJavascript(code, null) } }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    @JavascriptInterface fun getClientId(): String = prefs.getString("cid", "") ?: ""
    @JavascriptInterface fun setClientId(id: String) { prefs.edit().putString("cid", id.trim()).apply() }
    @JavascriptInterface fun isLinked(): Boolean = !prefs.getString("refresh", "").isNullOrEmpty()
    @JavascriptInterface fun logout() { prefs.edit().remove("access").remove("refresh").remove("exp").apply() }

    @JavascriptInterface fun login() {
        val cid = getClientId(); if (cid.isEmpty()) { js("window.__sp&&window.__sp('erro','Falta o Client ID')"); return }
        val rnd = ByteArray(48); SecureRandom().nextBytes(rnd)
        val ver = Base64.encodeToString(rnd, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val ch = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(ver.toByteArray()), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        prefs.edit().putString("ver", ver).apply()
        startServer()
        val url = "https://accounts.spotify.com/authorize?client_id=" + enc(cid) + "&response_type=code&redirect_uri=" + enc(redirect) +
            "&code_challenge_method=S256&code_challenge=" + ch + "&scope=" + enc(scopes)
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Pequeno servidor local só para receber a resposta do Spotify (sem escolher aplicações). */
    private fun startServer() {
        try { server?.close() } catch (e: Exception) { }
        val ss = try { java.net.ServerSocket(port, 1, java.net.InetAddress.getByName("127.0.0.1")) } catch (e: Exception) {
            js("window.__sp&&window.__sp('erro','porta " + port + " ocupada')"); return
        }
        server = ss
        Thread {
            try {
                ss.soTimeout = 180000
                while (true) {
                    val c = ss.accept()
                    c.soTimeout = 5000
                    val line = try { c.getInputStream().bufferedReader().readLine() ?: "" } catch (e: Exception) { "" }
                    val path = line.split(" ").getOrNull(1) ?: ""
                    val body = "<html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'></head><body style='background:#0b0d10;color:#fff;font-family:sans-serif;text-align:center;padding:48px 20px'><h2>Smart Hub ligado ao Spotify</h2><p>Já podes voltar à app Smart Hub.</p></body></html>"
                    val bytes = body.toByteArray()
                    try {
                        c.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + bytes.size + "\r\nConnection: close\r\n\r\n").toByteArray())
                        c.getOutputStream().write(bytes); c.getOutputStream().flush()
                    } catch (e: Exception) { }
                    try { c.close() } catch (e: Exception) { }
                    if (path.startsWith("/callback")) {
                        handleRedirect(Uri.parse("http://127.0.0.1:" + port + path))
                        try {
                            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                        } catch (e: Exception) { }
                        break
                    }
                }
            } catch (e: Exception) { }
            try { ss.close() } catch (e: Exception) { }
        }.start()
    }

    /** Chamado pela atividade quando o Spotify devolve o utilizador à app. */
    fun handleRedirect(uri: Uri) {
        val err = uri.getQueryParameter("error"); val code = uri.getQueryParameter("code")
        if (code == null) { js("window.__sp&&window.__sp('erro'," + JSONObject.quote(err ?: "login cancelado") + ")"); return }
        Thread {
            try {
                val r = form("grant_type=authorization_code&code=" + enc(code) + "&redirect_uri=" + enc(redirect) + "&client_id=" + enc(getClientId()) + "&code_verifier=" + enc(prefs.getString("ver", "") ?: ""))
                store(r); js("window.__sp&&window.__sp('ligado','')")
            } catch (e: Exception) { js("window.__sp&&window.__sp('erro'," + JSONObject.quote(e.message ?: "falhou") + ")") }
        }.start()
    }

    private fun form(body: String): JSONObject {
        val c = URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(body.toByteArray()) }
        val ok = c.responseCode in 200..299
        val txt = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        if (!ok) throw Exception(try { JSONObject(txt).optString("error_description", txt) } catch (e: Exception) { "HTTP " + c.responseCode })
        return JSONObject(txt)
    }

    private fun store(r: JSONObject) {
        val e = prefs.edit().putString("access", r.getString("access_token")).putLong("exp", System.currentTimeMillis() + r.optLong("expires_in", 3600) * 1000 - 60000)
        if (r.has("refresh_token")) e.putString("refresh", r.getString("refresh_token"))
        e.apply()
    }

    private fun token(): String {
        val t = prefs.getString("access", "") ?: ""
        if (t.isNotEmpty() && System.currentTimeMillis() < prefs.getLong("exp", 0)) return t
        val rt = prefs.getString("refresh", "") ?: ""
        if (rt.isEmpty()) throw Exception("Sem ligação ao Spotify")
        store(form("grant_type=refresh_token&refresh_token=" + enc(rt) + "&client_id=" + enc(getClientId())))
        return prefs.getString("access", "") ?: ""
    }

    /** Assíncrono: devolve a window.__spCb(cb, estado, texto). Só caminhos do leitor e da pesquisa. */
    @JavascriptInterface fun request(cb: String, method: String, path: String, body: String) {
        Thread {
            var st = 0; var txt = ""
            try {
                if (!path.startsWith("/v1/me/player") && !path.startsWith("/v1/search")) throw Exception("caminho não permitido")
                val c = URL("https://api.spotify.com" + path).openConnection() as HttpURLConnection
                c.requestMethod = method; c.connectTimeout = 10000; c.readTimeout = 15000
                c.setRequestProperty("Authorization", "Bearer " + token())
                if (method != "GET") { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
                st = c.responseCode
                txt = ((if (st in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText()) ?: ""
            } catch (e: Exception) { st = 0; txt = e.message ?: "erro" }
            js("window.__spCb&&window.__spCb(" + JSONObject.quote(cb) + "," + st + "," + JSONObject.quote(txt) + ")")
        }.start()
    }
}
