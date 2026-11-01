package pt.antunes.smarthub

import android.content.Context
import android.net.wifi.WifiManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

/** Controlo direto dos aparelhos Tuya pela rede de casa (sem passar pela nuvem). */
class LocalBridge(private val ctx: Context, private val web: WebView) {
    private val prefs = ctx.getSharedPreferences("tlocal", Context.MODE_PRIVATE)
    private val lock = Any()
    private var devs = JSONObject(prefs.getString("devs", "{}") ?: "{}")
    @Volatile private var lastScan = 0L

    private fun js(code: String) { web.post { web.evaluateJavascript(code, null) } }
    private fun saveDevs() { prefs.edit().putString("devs", devs.toString()).apply() }

    /** [{id,key}] vindos da Tuya. Mantém o IP e a versão já descobertos. */
    @JavascriptInterface fun setDevices(json: String) {
        try {
            val a = JSONArray(json)
            synchronized(lock) {
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i); val id = o.getString("id"); val key = o.optString("key", "")
                    if (key.length != 16) continue
                    val d = devs.optJSONObject(id) ?: JSONObject()
                    d.put("key", key); devs.put(id, d)
                }
                saveDevs()
            }
        } catch (e: Exception) { }
    }

    @JavascriptInterface fun status(): String {
        var keys = 0; var ips = 0
        synchronized(lock) { val it = devs.keys(); while (it.hasNext()) { val d = devs.optJSONObject(it.next()) ?: continue; if (d.optString("key").length == 16) keys++; if (d.optString("ip").isNotEmpty()) ips++ } }
        return JSONObject().put("keys", keys).put("ips", ips).toString()
    }

    private fun scanNow(ms: Int) {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val ml = try { wm?.createMulticastLock("smarthub")?.also { it.setReferenceCounted(false); it.acquire() } } catch (e: Exception) { null }
        try {
            TuyaLocal.listen(ms, object : TuyaLocal.Found {
                override fun on(id: String, ip: String, ver: String) {
                    synchronized(lock) {
                        val d = devs.optJSONObject(id) ?: JSONObject()
                        d.put("ip", ip); d.put("ver", ver); devs.put(id, d); saveDevs()
                    }
                }
            })
        } finally { try { ml?.release() } catch (e: Exception) { } }
        lastScan = System.currentTimeMillis()
    }

    /** Procura os aparelhos na rede (cerca de 8 segundos). Responde a window.__tlScan(chaves, comIp). */
    @JavascriptInterface fun scan() {
        Thread {
            scanNow(8000)
            js("window.__tlScan&&window.__tlScan(" + status() + ")")
        }.start()
    }

    private fun dev(id: String): TuyaLocal.Dev? {
        synchronized(lock) {
            val d = devs.optJSONObject(id) ?: return null
            if (d.optString("key").length != 16) return null
            return TuyaLocal.Dev(id, d.getString("key"), d.optString("ip"), d.optString("ver", "3.3"))
        }
    }

    private fun withIp(id: String): TuyaLocal.Dev? {
        var d = dev(id) ?: return null
        if (d.ip.isNullOrEmpty() && System.currentTimeMillis() - lastScan > 20000) { scanNow(4000); d = dev(id) ?: return null }
        return d
    }

    private fun reply(cb: String, ok: Boolean, text: String) {
        js("window.__tlCb&&window.__tlCb(" + JSONObject.quote(cb) + "," + ok + "," + JSONObject.quote(text) + ")")
    }

    /** Assíncrono. dps = objeto JSON de pontos de dados. Responde a window.__tlCb(cb, ok, mensagem). */
    @JavascriptInterface fun control(cb: String, id: String, dps: String) {
        Thread {
            val d = withIp(id)
            if (d == null || d.ip.isNullOrEmpty()) { reply(cb, false, "aparelho não encontrado na rede"); return@Thread }
            val err = TuyaLocal.control(d, dps)
            if (err != null && err.contains("sem resposta").not()) forget(id)
            reply(cb, err == null, err ?: "")
        }.start()
    }

    @JavascriptInterface fun query(cb: String, id: String) {
        Thread {
            val d = withIp(id)
            if (d == null || d.ip.isNullOrEmpty()) { reply(cb, false, "aparelho não encontrado na rede"); return@Thread }
            try { reply(cb, true, TuyaLocal.query(d)) } catch (e: Exception) { forget(id); reply(cb, false, e.message ?: "erro") }
        }.start()
    }

    /** Se falhar, o IP pode ter mudado: esquece-o para voltar a procurar. */
    private fun forget(id: String) {
        synchronized(lock) { devs.optJSONObject(id)?.remove("ip"); saveDevs() }
    }
}
