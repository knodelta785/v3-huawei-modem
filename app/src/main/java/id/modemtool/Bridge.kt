package id.modemtool

import android.app.Activity
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * Jembatan HTTP + kripto untuk UI (assets/index.html).
 * HTTP dilakukan native supaya tidak kena CORS dan cookie sesi modem terkelola.
 */
class Bridge(private val webView: WebView, private val activity: Activity) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private val cookies = LinkedHashMap<String, String>()

    @JavascriptInterface
    fun resetSession() {
        synchronized(cookies) { cookies.clear() }
    }

    @JavascriptInterface
    fun setCookie(nameValue: String) {
        val k = nameValue.substringBefore('=')
        val v = nameValue.substringAfter('=', "")
        synchronized(cookies) { cookies[k] = v }
    }

    @JavascriptInterface
    fun request(id: Int, method: String, url: String, headersJson: String, body: String) {
        thread {
            val out = JSONObject()
            try {
                val b = Request.Builder().url(url)
                val h = JSONObject(headersJson)
                h.keys().forEach { b.header(it, h.getString(it)) }
                b.header("X-Requested-With", "XMLHttpRequest")
                b.header("Referer", url.substringBefore("/api/") + "/html/home.html")
                synchronized(cookies) {
                    if (cookies.isNotEmpty()) {
                        b.header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
                    }
                }
                if (method == "POST") {
                    b.post(body.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaType()))
                } else {
                    b.get()
                }
                client.newCall(b.build()).execute().use { r ->
                    out.put("status", r.code)
                    out.put("body", r.body?.string() ?: "")
                    val tokens = JSONArray()
                    for (name in r.headers.names()) {
                        if (name.lowercase().startsWith("__requestverificationtoken")) {
                            r.headers(name).forEach { tokens.put(it) }
                        }
                    }
                    out.put("tokens", tokens)
                    r.headers("Set-Cookie").forEach { c ->
                        val nv = c.substringBefore(';')
                        val k = nv.substringBefore('=')
                        val v = nv.substringAfter('=', "")
                        synchronized(cookies) { cookies[k] = v }
                    }
                }
            } catch (e: Exception) {
                out.put("status", 0)
                out.put("body", "")
                out.put("tokens", JSONArray())
                out.put("error", e.javaClass.simpleName + ": " + (e.message ?: ""))
            }
            val js = "window.__native($id, ${JSONObject.quote(out.toString())})"
            activity.runOnUiThread { webView.evaluateJavascript(js, null) }
        }
    }

    // ---- Tes kualitas internet (latensi, jitter, unduh) ----

    private val testClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(14, TimeUnit.SECONDS)
        .build()

    @JavascriptInterface
    fun netTest(id: Int, dlBytes: Int) {
        thread {
            val out = JSONObject()
            val lats = JSONArray()
            var fail = 0
            repeat(8) {
                val t0 = System.nanoTime()
                try {
                    val rq = Request.Builder().url("http://connectivitycheck.gstatic.com/generate_204").head().build()
                    testClient.newCall(rq).execute().use { }
                    lats.put((System.nanoTime() - t0) / 1_000_000.0)
                } catch (e: Exception) {
                    fail++
                }
                try { Thread.sleep(150) } catch (_: InterruptedException) {}
            }
            var mbps = 0.0
            val urls = listOf(
                "https://speed.cloudflare.com/__down?bytes=$dlBytes",
                "https://proof.ovh.net/files/10Mb.dat"
            )
            for (u in urls) {
                try {
                    val t0 = System.nanoTime()
                    var total = 0L
                    testClient.newCall(Request.Builder().url(u).build()).execute().use { r ->
                        val src = r.body?.byteStream()
                        if (src != null) {
                            val buf = ByteArray(65536)
                            while (true) {
                                val n = src.read(buf)
                                if (n < 0) break
                                total += n
                                if ((System.nanoTime() - t0) > 8_000_000_000L) break
                            }
                        }
                    }
                    val sec = (System.nanoTime() - t0) / 1e9
                    if (total > 100_000 && sec > 0) { mbps = total * 8 / sec / 1e6; break }
                } catch (_: Exception) {}
            }
            out.put("lats", lats)
            out.put("fail", fail)
            out.put("dl", mbps)
            val js = "window.__native($id, ${JSONObject.quote(out.toString())})"
            activity.runOnUiThread { webView.evaluateJavascript(js, null) }
        }
    }

    // ---- Kripto login Huawei ----

    private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun hmac(key: ByteArray, msg: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(msg)
    }

    /** Login lama (password_type 4). */
    @JavascriptInterface
    fun hashLogin(user: String, pass: String, token: String): String {
        val inner = b64(hex(sha256(pass.toByteArray())).toByteArray())
        return b64(hex(sha256((user + inner + token).toByteArray())).toByteArray())
    }

    /** Login SCRAM (firmware baru). */
    @JavascriptInterface
    fun scramProof(password: String, saltHex: String, iterations: Int, firstNonce: String, serverNonce: String): String {
        val salt = ByteArray(saltHex.length / 2) { saltHex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        val salted = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        val authMsg = "$firstNonce,$serverNonce,$serverNonce"
        val clientKey = hmac("Client Key".toByteArray(), salted)
        val storedKey = sha256(clientKey)
        val signature = hmac(authMsg.toByteArray(), storedKey)
        val proof = ByteArray(clientKey.size) { (clientKey[it].toInt() xor signature[it].toInt()).toByte() }
        return hex(proof)
    }

    @JavascriptInterface
    fun randomHex(bytes: Int): String {
        val b = ByteArray(bytes)
        java.security.SecureRandom().nextBytes(b)
        return hex(b)
    }
}
