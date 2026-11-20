package app.mashix.vpn

import android.app.Activity
import android.content.Intent
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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.StringReader
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var backend: GoBackend
    private val worker = Executors.newSingleThreadExecutor()
    private var wireGuardConfig: Config? = null
    private var configName: String = ""

    private val tunnel = object : Tunnel {
        override fun getName(): String = "mashix"
        override fun onStateChange(newState: Tunnel.State) {
            runOnUiThread {
                val up = newState == Tunnel.State.UP
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
        if (result.resultCode == Activity.RESULT_OK) startWireGuard() else notifyPage(false, "Разрешение Android на VPN не выдано")
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
        @JavascriptInterface fun connect() {
            runOnUiThread {
                if (wireGuardConfig == null) {
                    notifyPage(false, "Сначала импортируй файл WireGuard .conf")
                    return@runOnUiThread
                }
                val permissionIntent = VpnService.prepare(this@MainActivity)
                if (permissionIntent != null) vpnPermission.launch(permissionIntent)
                else startWireGuard()
            }
        }
        @JavascriptInterface fun disconnect() {
            worker.execute {
                try {
                    backend.setState(tunnel, Tunnel.State.DOWN, null)
                    notifyPage(false, "VPN отключён")
                } catch (e: Exception) {
                    notifyPage(false, "Не удалось отключить VPN: ${e.message ?: "ошибка"}")
                }
            }
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
                if(hero.classList.contains('connected')) MashixNative.disconnect();
                else { const s=document.getElementById('state'); if(s)s.textContent='Подключение…'; MashixNative.connect(); }
              }; }
              window.mashixVpnStatus=(up,msg)=>{
                const hero=document.getElementById('hero'), state=document.getElementById('state'), hint=document.getElementById('hint');
                if(hero){hero.classList.remove('connecting','connected'); if(up)hero.classList.add('connected');}
                if(state)state.textContent=up?'Подключено':(msg.includes('Подключение')?'Подключение…':'Отключено');
                if(hint)hint.textContent=msg;
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
