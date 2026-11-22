package app.mashix.vpn

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var backend: GoBackend
    private val worker = Executors.newSingleThreadExecutor()
    private var wireGuardConfig: Config? = null
    private var configName: String = ""
    private var wgConnected: Boolean = false
    private var selectedKind: String = ""
    private var selectedName: String = ""
    private var selectedPayload: String = ""
    private var vpnGrantAction: (() -> Unit)? = null

    private val tunnel = object : Tunnel {
        override fun getName(): String = "mashix"
        override fun onStateChange(newState: Tunnel.State) {
            runOnUiThread {
                val up = newState == Tunnel.State.UP
                wgConnected = up
                notifyPage(up, if (up) "VPN подключён через WireGuard" else "VPN отключён")
            }
        }
    }

    private val configPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val raw = BufferedReader(InputStreamReader(stream)).readText()
                    wireGuardConfig = Config.parse(BufferedReader(StringReader(raw)))
                    configName = uri.lastPathSegment?.substringAfterLast('/')?.take(48) ?: "WireGuard.conf"
                    notifyConfigLoaded(configName)
                } ?: notifyPage(false, "Не удалось прочитать файл конфигурации")
            } catch (e: Exception) {
                wireGuardConfig = null
                notifyPage(false, "Файл не распознан как конфигурация WireGuard: ${e.message ?: "ошибка"}")
            }
        }
    }

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val action = vpnGrantAction
        vpnGrantAction = null
        if (result.resultCode == Activity.RESULT_OK) action?.invoke()
        else notifyPage(false, "Разрешение Android на VPN не выдано")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backend = GoBackend(this)
        webView = WebView(this)
        setContentView(webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = false
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(NativeBridge(), "MashixNative")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                installNativeControls()
            }
        }
        webView.loadUrl("file:///android_asset/index.html")
    }

    private inner class NativeBridge {
        @JavascriptInterface fun chooseConfig() {
            runOnUiThread { configPicker.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) }
        }
        @JavascriptInterface fun importConfig(name: String?, text: String?) {
            val safeText = text?.trim() ?: return
            if (safeText.isEmpty()) return
            try {
                val parsed = Config.parse(BufferedReader(StringReader(safeText)))
                runOnUiThread {
                    wireGuardConfig = parsed
                    configName = (name ?: "WireGuard").trim().take(48).ifEmpty { "WireGuard" }
                    notifyConfigLoaded(configName)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    notifyPage(false, "Ссылка/QR не распознаны как конфигурация WireGuard: ${e.message ?: "ошибка"}")
                }
            }
        }

        @JavascriptInterface fun connect() {
            runOnUiThread {
                if (wireGuardConfig == null) {
                    notifyPage(false, "Сначала импортируй файл WireGuard .conf")
                    return@runOnUiThread
                }
                askVpnPermission { startWireGuard() }
            }
        }
        @JavascriptInterface fun disconnect() {
            disconnectAll()
        }
        @JavascriptInterface fun selectConfig(kind: String?, name: String?, payload: String?) {
            selectedKind = kind?.trim() ?: ""
            selectedName = (name ?: "").trim().take(48)
            selectedPayload = payload?.trim() ?: ""
        }
        @JavascriptInterface fun togglePower() {
            runOnUiThread {
                if (wgConnected) {
                    disconnectAll()
                    return@runOnUiThread
                }
                when (selectedKind) {
                    "wireguard" -> {
                        if (wireGuardConfig == null) {
                            notifyPage(false, "Сначала импортируй конфигурацию WireGuard")
                            return@runOnUiThread
                        }
                        notifyPage(false, "Подключение WireGuard…")
                        askVpnPermission { startWireGuard() }
                    }
                    "vless", "vmess", "trojan", "ss" -> notifyPage(false, "Соединение VLESS/vmess появится в следующем обновлении — пока работают WireGuard-серверы")
                    else -> notifyPage(false, "Сначала выбери сервер в разделе «Локации»")
                }
            }
        }
        @JavascriptInterface fun fetchSubscription(url: String?) {
            fetchSubscription(url, "add")
        }

        @JavascriptInterface fun fetchSubscription(url: String?, mode: String?) {
            val safeUrl = url?.trim() ?: return
            if (!safeUrl.startsWith("http://") && !safeUrl.startsWith("https://")) return
            val safeMode = if (mode == "refresh") "refresh" else "add"
            worker.execute {
                // Панели отдают разные конфиги разным приложениям: перебираем несколько
                // «личностей», пока какая-нибудь не вернёт хотя бы один реальный сервер.
                val identities = listOf(
                    "v2rayN/7.10.2", "Happ/1.5.1", "v2rayNG/1.10.10", "Streisand/2.0.0",
                    "Hiddify/2.5.7", "Shadowrocket/2.2.62"
                )
                var best: JSONArray? = null
                var lastNote = ""
                for (ua in identities) {
                    try {
                        val conn = URL(safeUrl).openConnection() as HttpURLConnection
                        conn.connectTimeout = 8000
                        conn.readTimeout = 8000
                        conn.instanceFollowRedirects = true
                        conn.setRequestProperty("User-Agent", ua)
                        val body = conn.inputStream.use { inp ->
                            BufferedReader(InputStreamReader(inp, Charsets.UTF_8)).readText()
                        }
                        val servers = XrayLinkParser.parseSubscription(body)
                        if (servers.length() > 0) {
                            best = servers
                            break
                        }
                        lastNote = "панель отвечает, но реальных серверов не отдаёт"
                    } catch (e: Exception) {
                        lastNote = "ошибка сети: ${e.message ?: "неизвестно"}"
                    }
                }
                val found = best
                val payload = JSONObject()
                    .put("ok", found != null)
                    .put("url", safeUrl)
                    .put("mode", safeMode)
                    .put("servers", found ?: JSONArray())
                    .put(
                        "note",
                        if (found != null) "Найдено серверов: ${found.length()}"
                        else "Панель не отдала ни одного реального сервера (проверено 6 форматов клиентов). Проверь подписку у провайдера — $lastNote"
                    )
                emitSubResult(payload)
            }
        }

        /** Pings host:port list [{"id":"3","host":"de1.example.com","port":443}] and reports
         *  each result to window.mashixPing(id, ms) — ms<0 means no connection. */
        @JavascriptInterface fun pingAll(itemsJson: String?) {
            val arr = try { JSONArray(itemsJson ?: "[]") } catch (_: Exception) { JSONArray() }
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            val pool = Executors.newFixedThreadPool(4)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val host = o.optString("host")
                val port = o.optInt("port", 0)
                pool.execute {
                    val ms = if (host.isEmpty() || port <= 0) -1L else try {
                        val start = System.currentTimeMillis()
                        java.net.Socket().use { sock ->
                            sock.connect(java.net.InetSocketAddress(host, port), 3000)
                        }
                        System.currentTimeMillis() - start
                    } catch (_: Exception) { -1L }
                    main.post {
                        webView.evaluateJavascript("window.mashixPing && window.mashixPing('$id', $ms);", null)
                    }
                }
            }
            pool.shutdown()
        }

        /** Opens the Android share sheet (Поделиться в меню «⋯»). */
        @JavascriptInterface fun shareText(text: String?) {
            val safe = text?.trim() ?: return
            if (safe.isEmpty()) return
            runOnUiThread {
                try {
                    val intent = Intent(Intent.ACTION_SEND)
                    intent.type = "text/plain"
                    intent.putExtra(Intent.EXTRA_TEXT, safe)
                    startActivity(Intent.createChooser(intent, "Поделиться серверами"))
                } catch (_: Exception) {}
            }
        }
    }

    private fun emitSubResult(payload: JSONObject) {
        val safe = payload.toString().replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")
        runOnUiThread { webView.evaluateJavascript("window.mashixSubResult && window.mashixSubResult('$safe');", null) }
    }

    private fun askVpnPermission(action: () -> Unit) {
        val intent = VpnService.prepare(this)
        if (intent == null) action() else {
            vpnGrantAction = action
            vpnPermission.launch(intent)
        }
    }

    private fun startWireGuard() {
        val config = wireGuardConfig ?: return notifyPage(false, "Сначала импортируй конфигурацию WireGuard")
        notifyPage(false, "Подключение WireGuard…")
        worker.execute {
            try {
                backend.setState(tunnel, Tunnel.State.UP, config)
                notifyPage(true, "Подключено: $configName")
            } catch (e: Exception) {
                notifyPage(false, "Ошибка WireGuard: ${e.message ?: "проверь конфигурацию и сеть"}")
            }
        }
    }

    private fun disconnectAll() {
        if (wgConnected) {
            worker.execute {
                try {
                    backend.setState(tunnel, Tunnel.State.DOWN, null)
                } catch (e: Exception) {
                    notifyPage(false, "Не удалось отключить VPN: ${e.message ?: "ошибка"}")
                }
            }
            wgConnected = false
        }
        notifyPage(false, "VPN отключён")
    }

    private fun installNativeControls() {
        val js = """
            (function(){
              const actions=document.querySelector('.import-actions');
              if(actions && !document.getElementById('nativeWgImport')){
                const b=document.createElement('button'); b.id='nativeWgImport'; b.className='import-btn';
                b.style.gridColumn='1 / -1'; b.textContent='Импортировать WireGuard .conf';
                b.onclick=()=>MashixNative.chooseConfig(); actions.appendChild(b);
              }
              const p=document.getElementById('power');
              if(p){ p.onclick=()=>{
                const hero=document.getElementById('hero');
                if(!hero.classList.contains('connected')){ const s=document.getElementById('state'); if(s)s.textContent='Подключение…'; }
                MashixNative.togglePower();
              }; }
              window.mashixVpnStatus=(up,msg)=>{
                const hero=document.getElementById('hero'), state=document.getElementById('state'), hint=document.getElementById('hint');
                if(hero){hero.classList.remove('connecting','connected'); if(up)hero.classList.add('connected');}
                if(state)state.textContent=up?'Подключено':(String(msg).includes('Подключение')?'Подключение…':'Отключено');
                if(hint)hint.textContent=msg;
                if(window.__mashixTimer){clearInterval(window.__mashixTimer);window.__mashixTimer=null;}
                const t=document.getElementById('timer'), d=document.getElementById('downRate'), u=document.getElementById('upRate');
                if(up){ let s=0; window.__mashixTimer=setInterval(()=>{ s++; const h=Math.floor(s/3600), m=Math.floor(s/60)%60, x=s%60; const mm=String(m).padStart(2,'0'), xx=String(x).padStart(2,'0'); if(t)t.textContent=h>0?(h+':'+mm+':'+xx):(mm+':'+xx); },1000); }
                else { if(t)t.textContent='00:00'; if(d)d.textContent='0 B/s'; if(u)u.textContent='0 B/s'; }
              };
              window.mashixConfigLoaded=(name)=>{
                const b=document.getElementById('nativeWgImport'); if(b)b.textContent='✓ WireGuard: '+name;
                const h=document.getElementById('hint'); if(h)h.textContent='Конфигурация загружена. Нажми кнопку питания для подключения.';
              };
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun notifyPage(connected: Boolean, message: String) {
        val safe = message.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")
        runOnUiThread { webView.evaluateJavascript("window.mashixVpnStatus && window.mashixVpnStatus($connected, '$safe');", null) }
    }

    private fun notifyStats(upBytes: Long, downBytes: Long) {
        runOnUiThread { webView.evaluateJavascript("window.mashixStats && window.mashixStats($upBytes, $downBytes);", null) }
    }

    private fun notifyConfigLoaded(name: String) {
        val safe = name.replace("\\", "\\\\").replace("'", "\\'")
        webView.evaluateJavascript("window.mashixConfigLoaded && window.mashixConfigLoaded('$safe');", null)
    }

    override fun onDestroy() {
        webView.removeJavascriptInterface("MashixNative")
        webView.destroy()
        worker.shutdown()
        super.onDestroy()
    }
}

/**
 * Converts VPN sharing links (vless://, vmess://, trojan://, ss://) into a
 * full Xray-core JSON configuration with a TUN inbound, and parses
 * subscription responses (base64 link lists or sing-box JSON) into links.
 */
object XrayLinkParser {
    private val supportedSchemes = listOf("vless", "vmess", "trojan", "ss", "ssr", "hysteria", "hysteria2", "tuic")

    fun buildConfig(link: String): String {
        val outbound = parseLink(link)
        val cfg = JSONObject()
        cfg.put("log", JSONObject().put("loglevel", "warning"))
        cfg.put("dns", JSONObject().put("servers", JSONArray().put("https://8.8.8.8/dns-query")))
        val tunIn = JSONObject()
            .put("protocol", "tun")
            .put("tag", "tun-in")
            .put("settings", JSONObject().put("mtu", 8500))
        cfg.put("inbounds", JSONArray().put(tunIn))
        cfg.put(
            "outbounds",
            JSONArray()
                .put(outbound)
                .put(JSONObject().put("protocol", "dns").put("tag", "dns-out"))
                .put(JSONObject().put("protocol", "freedom").put("tag", "direct"))
        )
        val rules = JSONArray()
            .put(
                JSONObject()
                    .put("type", "field")
                    .put("inboundTag", JSONArray().put("tun-in"))
                    .put("port", "53")
                    .put("outboundTag", "dns-out")
            )
            .put(
                JSONObject()
                    .put("type", "field")
                    .put("ip", JSONArray().put("private"))
                    .put("outboundTag", "direct")
            )
        cfg.put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", rules))
        return cfg.toString()
    }

    private fun parseLink(raw: String): JSONObject {
        val link = raw.trim()
        val scheme = link.substringBefore("://", "").lowercase()
        return when (scheme) {
            "vless" -> parseVless(link)
            "vmess" -> parseVmess(link)
            "trojan" -> parseTrojan(link)
            "ss" -> parseSs(link)
            else -> throw IllegalArgumentException("протокол '$scheme' не поддерживается (vless/vmess/trojan/ss)")
        }
    }

    private fun jarr(items: List<String>): JSONArray {
        val a = JSONArray()
        items.forEach { if (it.isNotEmpty()) a.put(it) }
        return a
    }

    private fun b64(value: String): String {
        val t = value.trim().replace('-', '+').replace('_', '/').trimEnd('=')
        val pad = (4 - (t.length % 4)) % 4
        return String(Base64.getDecoder().decode(t + "=".repeat(pad)), Charsets.UTF_8)
    }

    private fun putStreamSettings(stream: JSONObject, netIn: String, securityIn: String, sni: String, fp: String,
                                  pbk: String, sid: String, hostHeader: String, path: String,
                                  serviceName: String, alpn: String, insecure: Boolean, fallbackHost: String) {
        val net = netIn.lowercase()
        val security = securityIn.lowercase()
        when (net) {
            "tcp" -> {}
            "ws" -> {
                val ws = JSONObject()
                ws.put("path", path.ifEmpty { "/" })
                if (hostHeader.isNotEmpty()) ws.put("headers", JSONObject().put("Host", hostHeader))
                stream.put("wsSettings", ws)
            }
            "grpc" -> stream.put("grpcSettings", JSONObject().put("serviceName", serviceName))
            "http", "h2" -> {
                val h = JSONObject()
                h.put("path", path.ifEmpty { "/" })
                if (hostHeader.isNotEmpty()) h.put("host", jarr(listOf(hostHeader)))
                stream.put("httpSettings", h)
            }
            else -> throw IllegalArgumentException("тип сети '$net' не поддерживается")
        }
        when (security) {
            "tls" -> {
                val tls = JSONObject()
                val serverName = sni.ifEmpty { hostHeader.ifEmpty { fallbackHost } }
                if (serverName.isNotEmpty()) tls.put("serverName", serverName)
                if (alpn.isNotEmpty()) tls.put("alpn", jarr(alpn.split(',')))
                if (fp.isNotEmpty()) tls.put("fingerprint", fp)
                if (insecure) tls.put("allowInsecure", true)
                stream.put("security", "tls")
                stream.put("tlsSettings", tls)
            }
            "reality" -> {
                if (pbk.isEmpty()) throw IllegalArgumentException("reality требует параметр pbk (publicKey)")
                val reality = JSONObject()
                reality.put("publicKey", pbk)
                if (sid.isNotEmpty()) reality.put("shortId", sid)
                reality.put("serverName", sni.ifEmpty { hostHeader.ifEmpty { fallbackHost } })
                reality.put("fingerprint", fp.ifEmpty { "chrome" })
                stream.put("security", "reality")
                stream.put("realitySettings", reality)
            }
            "none", "" -> stream.put("security", "none")
            else -> throw IllegalArgumentException("security '$security' не поддерживается")
        }
        stream.put("network", net)
    }

    private fun parseVless(link: String): JSONObject {
        val uri = Uri.parse(link)
        val uuid = uri.userInfo ?: throw IllegalArgumentException("в ссылке нет UUID")
        val host = (uri.host ?: "").trim('[', ']')
        if (host.isEmpty()) throw IllegalArgumentException("в ссылке нет адреса сервера")
        val port = if (uri.port > 0) uri.port else 443
        fun q(vararg keys: String): String {
            for (k in keys) {
                val v = uri.getQueryParameter(k)
                if (!v.isNullOrEmpty()) return v
            }
            return ""
        }
        val encryption = q("encryption").lowercase()
        if (encryption.isNotEmpty() && encryption != "none") {
            throw IllegalArgumentException("encryption '$encryption' не поддерживается")
        }
        val flow = q("flow")
        val net = q("type", "network").ifEmpty { "tcp" }
        val stream = JSONObject()
        putStreamSettings(
            stream, net,
            q("security"), q("sni", "serverName"), q("fp", "fingerprint"),
            q("pbk", "publicKey"), q("sid", "shortId"),
            q("host"), q("path"), q("serviceName", "serviceName"),
            q("alpn"), q("allowInsecure", "insecure").let { it == "1" || it.equals("true", true) },
            host
        )
        val user = JSONObject()
            .put("id", uuid)
            .put("encryption", "none")
        if (flow.isNotEmpty() && net == "tcp") user.put("flow", flow)
        val vnext = JSONObject()
            .put("address", host)
            .put("port", port)
            .put("users", JSONArray().put(user))
        return JSONObject()
            .put("protocol", "vless")
            .put("tag", "proxy")
            .put("settings", JSONObject().put("vnext", JSONArray().put(vnext)))
            .put("streamSettings", stream)
    }

    private fun parseVmess(link: String): JSONObject {
        val json = JSONObject(b64(link.removePrefix("vmess://").substringBefore('#')))
        val host = json.optString("add").trim('[', ']')
        if (host.isEmpty()) throw IllegalArgumentException("в ссылке нет адреса сервера")
        val port = json.optString("port").toIntOrNull() ?: 443
        val net = json.optString("net", "tcp")
        val tlsOn = json.optString("tls", "").lowercase() == "tls"
        val stream = JSONObject()
        putStreamSettings(
            stream, net,
            if (tlsOn) "tls" else "none",
            json.optString("sni"), json.optString("fp"),
            "", "", json.optString("host"), json.optString("path"),
            "", json.optString("alpn"), false, host
        )
        val user = JSONObject()
            .put("id", json.optString("id"))
            .put("alterId", json.optString("aid", "0").toIntOrNull() ?: 0)
            .put("security", json.optString("scy", "auto").ifEmpty { "auto" })
        val vnext = JSONObject()
            .put("address", host)
            .put("port", port)
            .put("users", JSONArray().put(user))
        return JSONObject()
            .put("protocol", "vmess")
            .put("tag", "proxy")
            .put("settings", JSONObject().put("vnext", JSONArray().put(vnext)))
            .put("streamSettings", stream)
    }

    private fun parseTrojan(link: String): JSONObject {
        val uri = Uri.parse(link)
        val password = uri.userInfo ?: throw IllegalArgumentException("в ссылке нет пароля")
        val host = (uri.host ?: "").trim('[', ']')
        if (host.isEmpty()) throw IllegalArgumentException("в ссылке нет адреса сервера")
        val port = if (uri.port > 0) uri.port else 443
        fun q(vararg keys: String): String {
            for (k in keys) {
                val v = uri.getQueryParameter(k)
                if (!v.isNullOrEmpty()) return v
            }
            return ""
        }
        val stream = JSONObject()
        putStreamSettings(
            stream, q("type").ifEmpty { "tcp" },
            q("security").ifEmpty { "tls" }, q("sni"), q("fp"),
            "", "", q("host"), q("path"), q("serviceName"),
            q("alpn"), q("allowInsecure", "insecure").let { it == "1" || it.equals("true", true) },
            host
        )
        val server = JSONObject()
            .put("address", host)
            .put("port", port)
            .put("password", password)
        return JSONObject()
            .put("protocol", "trojan")
            .put("tag", "proxy")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
            .put("streamSettings", stream)
    }

    private fun parseSs(link: String): JSONObject {
        val body = link.removePrefix("ss://").substringBefore('#').trim()
        val decoded = if (body.contains('@')) {
            val creds = b64(body.substringBefore('@'))
            "$creds@${body.substringAfter('@')}"
        } else {
            b64(body)
        }
        val main = decoded.substringBefore('?')
        val userinfo = main.substringBefore('@')
        val hostport = main.substringAfter('@')
        val method = userinfo.substringBefore(':')
        val password = userinfo.substringAfter(':', missingDelimiterValue = "")
        val host = hostport.substringBeforeLast(':').trim('[', ']')
        val port = hostport.substringAfterLast(':').toIntOrNull()
        if (method.isEmpty() || host.isEmpty() || port == null) {
            throw IllegalArgumentException("ссылка ss:// не распознана")
        }
        val server = JSONObject()
            .put("address", host)
            .put("port", port)
            .put("method", method)
            .put("password", password)
        return JSONObject()
            .put("protocol", "shadowsocks")
            .put("tag", "proxy")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
    }

    /** Parses a subscription body (sing-box JSON or base64/plain link list) into a JSONArray of {name, type, link}. */
    fun parseSubscription(body: String): JSONArray {
        val trimmed = body.trim()
        val out = JSONArray()
        if (trimmed.startsWith("{")) {
            val root = JSONObject(trimmed)
            val obOutbounds = root.optJSONArray("outbounds") ?: return out
            for (i in 0 until obOutbounds.length()) {
                val ob = obOutbounds.optJSONObject(i) ?: continue
                val type = ob.optString("type").lowercase()
                val tag = ob.optString("tag", "").ifEmpty { ob.optString("server") }
                val server = ob.optString("server").trim('[', ']')
                val port = ob.optInt("server_port", 0)
                if (server.isEmpty() || port <= 1 || server == "0.0.0.0" || tag.contains("не поддерживается")) continue
                val link = singboxToLink(type, ob, server, port, tag) ?: continue
                out.put(JSONObject().put("name", tag).put("type", type.uppercase()).put("link", link))
            }
            return out
        }
        val text = try { b64(trimmed) } catch (_: Exception) { trimmed }
        for (line in text.lines()) {
            val l = line.trim()
            if (l.isEmpty()) continue
            val scheme = l.substringBefore("://", "").lowercase()
            if (scheme !in supportedSchemes) continue
            if (l.contains("@0.0.0.0:") || l.contains("@127.0.0.1:")) continue
            val name = try { Uri.parse(l).fragment?.let { java.net.URLDecoder.decode(it, "UTF-8") } } catch (_: Exception) { null }
            out.put(JSONObject().put("name", name?.take(48) ?: scheme.uppercase()).put("type", scheme.uppercase()).put("link", l))
        }
        return out
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun singboxToLink(type: String, ob: JSONObject, server: String, port: Int, tag: String): String? {
        val tls = ob.optJSONObject("tls")
        val transport = ob.optJSONObject("transport")
        val sni = tls?.optString("server_name", "") ?: ""
        val fp = tls?.optJSONObject("utls")?.optString("fingerprint", "") ?: ""
        val reality = tls?.optJSONObject("reality")
        val flow = ob.optString("flow", "")
        val net = transport?.optString("type", "") ?: ""
        val path = transport?.optString("path", "") ?: ""
        val hostHeader = transport?.optJSONObject("headers")?.optString("Host", "") ?: ""
        val svc = transport?.optString("service_name", "") ?: ""
        return when (type) {
            "vless" -> {
                val params = mutableListOf<String>()
                params.add("encryption=none")
                if (flow.isNotEmpty()) params.add("flow=$flow")
                val isReality = reality?.optBoolean("enabled", false) == true
                if (isReality) {
                    params.add("security=reality")
                    params.add("pbk=${reality?.optString("public_key", "")}")
                    val sid = reality?.optString("short_id", "") ?: ""
                    if (sid.isNotEmpty()) params.add("sid=$sid")
                } else if (tls?.optBoolean("enabled", false) == true) {
                    params.add("security=tls")
                }
                if (sni.isNotEmpty()) params.add("sni=$sni")
                if (fp.isNotEmpty()) params.add("fp=$fp")
                if (net.isNotEmpty()) params.add("type=$net")
                if (path.isNotEmpty()) params.add("path=$path")
                if (hostHeader.isNotEmpty()) params.add("host=$hostHeader")
                if (svc.isNotEmpty()) params.add("serviceName=$svc")
                val uuid = ob.optString("uuid")
                if (uuid.isEmpty()) return null
                "vless://$uuid@$server:$port?${params.joinToString("&")}#${enc(tag)}"
            }
            "vmess" -> {
                val v = JSONObject()
                    .put("v", "2")
                    .put("ps", tag)
                    .put("add", server)
                    .put("port", port.toString())
                    .put("id", ob.optString("uuid"))
                    .put("aid", ob.optString("alter_id", "0"))
                    .put("scy", ob.optString("security", "auto").ifEmpty { "auto" })
                    .put("net", net.ifEmpty { "tcp" })
                    .put("host", hostHeader)
                    .put("path", path)
                    .put("tls", if (tls?.optBoolean("enabled", false) == true) "tls" else "")
                    .put("sni", sni)
                    .put("fp", fp)
                val bytes = v.toString().toByteArray(Charsets.UTF_8)
                "vmess://" + Base64.getEncoder().encodeToString(bytes)
            }
            "trojan" -> {
                val password = ob.optString("password")
                if (password.isEmpty()) return null
                val params = mutableListOf<String>()
                params.add("security=tls")
                if (sni.isNotEmpty()) params.add("sni=$sni")
                if (fp.isNotEmpty()) params.add("fp=$fp")
                if (net.isNotEmpty()) params.add("type=$net")
                if (path.isNotEmpty()) params.add("path=$path")
                if (svc.isNotEmpty()) params.add("serviceName=$svc")
                "trojan://$password@$server:$port?${params.joinToString("&")}#${enc(tag)}"
            }
            "shadowsocks" -> {
                val method = ob.optString("method")
                val password = ob.optString("password")
                if (method.isEmpty() || password.isEmpty()) return null
                val creds = Base64.getEncoder().encodeToString("$method:$password".toByteArray(Charsets.UTF_8))
                "ss://$creds@$server:$port#${enc(tag)}"
            }
            else -> null
        }
    }
}
