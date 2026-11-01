package com.antunes.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

/** Comando da TV (Google TV / TCL) para a página: emparelhar, ligar, teclas e abrir apps. */
class TvBridge(private val ctx: Context, private val web: WebView) {
    private val prefs = ctx.getSharedPreferences("tv", Context.MODE_PRIVATE)
    private var client: AtvClient? = null
    private var pair: AtvClient? = null
    private var nsd: NsdManager? = null
    private var disc: NsdManager.DiscoveryListener? = null

    init { AtvClient.filesDir = ctx.filesDir }

    private fun js(ev: String, v: String) {
        web.post { web.evaluateJavascript("window.__tv&&window.__tv(" + JSONObject.quote(ev) + "," + JSONObject.quote(v) + ")", null) }
    }

    @JavascriptInterface fun getHost(): String = prefs.getString("host", "") ?: ""
    @JavascriptInterface fun setHost(h: String) { prefs.edit().putString("host", h.trim()).apply() }

    @JavascriptInterface fun connect() {
        val host = getHost(); if (host.isEmpty()) { js("state", "SEM_IP"); return }
        if (client?.connected() == true) { js("state", "OK"); return }
        client?.stop()
        val c = AtvClient(host, object : AtvClient.Listener {
            override fun onState(state: String, connected: Boolean) { js("state", if (connected) "OK" else state) }
        })
        client = c
        Thread { c.run() }.start()
    }

    @JavascriptInterface fun disconnect() { client?.stop(); client = null }
    @JavascriptInterface fun key(code: Int) { client?.key(code) }
    @JavascriptInterface fun open(link: String) { client?.open(link) }
    @JavascriptInterface fun type(t: String) { client?.type(t) }
    @JavascriptInterface fun isConnected(): Boolean = client?.connected() == true

    @JavascriptInterface fun pairStart() {
        val host = getHost(); if (host.isEmpty()) { js("pair", "erro:Falta o IP da TV"); return }
        pair?.stop()
        val p = AtvClient(host, object : AtvClient.Listener { override fun onState(state: String, connected: Boolean) {} })
        pair = p
        Thread {
            try { p.startPairing(); js("pair", "codigo") } catch (e: Exception) { js("pair", "erro:" + (e.message ?: "falhou")) }
        }.start()
    }

    @JavascriptInterface fun pairFinish(code: String) {
        val p = pair ?: return
        Thread {
            try { p.finishPairing(code); pair = null; js("pair", "ok"); connect() } catch (e: Exception) { js("pair", "erro:" + (e.message ?: "falhou")) }
        }.start()
    }

    @JavascriptInterface fun discover() {
        stopDisc()
        val m = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager; nsd = m
        val l = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(t: String?, e: Int) { js("found", "") }
            override fun onStopDiscoveryFailed(t: String?, e: Int) {}
            override fun onDiscoveryStarted(t: String?) {}
            override fun onDiscoveryStopped(t: String?) {}
            override fun onServiceLost(s: NsdServiceInfo?) {}
            override fun onServiceFound(s: NsdServiceInfo) {
                try {
                    m.resolveService(s, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(si: NsdServiceInfo?, e: Int) {}
                        override fun onServiceResolved(si: NsdServiceInfo) {
                            val ip = si.host?.hostAddress ?: return
                            if (ip.contains(":")) return
                            setHost(ip); js("found", ip); stopDisc()
                        }
                    })
                } catch (e: Exception) {}
            }
        }
        disc = l
        try { m.discoverServices("_androidtvremote2._tcp.", NsdManager.PROTOCOL_DNS_SD, l) } catch (e: Exception) { js("found", "") }
    }

    private fun stopDisc() { try { disc?.let { nsd?.stopServiceDiscovery(it) } } catch (e: Exception) {}; disc = null }
}
