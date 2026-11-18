package ru.pocketagent

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Обновления: маленький манифест JSON (свой формат или GitHub Releases API) → APK → проверка sha256 и подписи → установка
 * только с подтверждением пользователя (PackageInstaller). Источник — BuildConfig.UPDATE_MANIFEST_URL (пусто — выключено).
 */
object Updater {
    data class Info(val versionCode: Long, val versionName: String, val apkUrl: String, val sha256: String,
                    val changelog: String, val minSdk: Int = 0, val size: Long = 0)

    class UpdateError(msg: String) : Exception(msg)

    const val DAY_MS = 24L * 3600 * 1000
    private const val P = "updates"

    fun url(): String = BuildConfig.UPDATE_MANIFEST_URL.trim()
    fun enabled() = url().isNotEmpty()

    // ---------- разбор манифеста (чистые функции, покрыты тестами)

    private val SHA_RE = Regex("(?i)sha-?256[^0-9a-f]{0,20}([0-9a-f]{64})")
    private val VC_RE = Regex("(?i)version_?code\\W{0,5}(\\d+)")

    fun parse(json: String): Info {
        val o = try { JSONObject(json.trim()) } catch (e: Exception) { throw UpdateError("манифест обновления повреждён") }
        if (o.has("assets") || o.has("tag_name")) return parseGitHub(o)
        val code = o.optLong("versionCode", -1)
        val apk = o.optString("apkUrl", "")
        val sha = o.optString("sha256", "").lowercase()
        if (code <= 0 || apk.isBlank()) throw UpdateError("в манифесте нет versionCode/apkUrl")
        if (!sha.matches(Regex("[0-9a-f]{64}"))) throw UpdateError("в манифесте нет корректного sha256")
        return Info(code, o.optString("versionName", code.toString()), apk, sha,
            o.optString("changelog_ru", o.optString("changelog", "")), o.optInt("minSdk", 0), o.optLong("size", 0))
    }

    /** GitHub releases/latest: asset *.apk + «sha256: …» и «versionCode: N» в описании релиза. */
    fun parseGitHub(o: JSONObject): Info {
        val body = o.optString("body", "")
        val assets = o.optJSONArray("assets")
        var apk = ""; var size = 0L
        if (assets != null) for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk", true) && (apk.isEmpty() || a.optString("name").contains("lite", true))) {
                apk = a.optString("browser_download_url"); size = a.optLong("size", 0)
            }
        }
        if (apk.isBlank()) throw UpdateError("в релизе нет файла .apk")
        val sha = SHA_RE.find(body)?.groupValues?.get(1)?.lowercase() ?: throw UpdateError("в описании релиза нет sha256")
        val name = o.optString("tag_name").removePrefix("v").ifBlank { o.optString("name") }
        val code = VC_RE.find(body)?.groupValues?.get(1)?.toLongOrNull() ?: codeFromName(name)
        val notes = body.lines().filterNot { SHA_RE.containsMatchIn(it) || VC_RE.containsMatchIn(it) }.joinToString("\n").trim()
        return Info(code, name, apk, sha, notes, 0, size)
    }

    /** «2.3.1» → 2003001 (запасной вариант, если versionCode не указан). */
    fun codeFromName(name: String): Long {
        val parts = Regex("\\d+").findAll(name).map { it.value.toLong() }.toList()
        if (parts.isEmpty()) throw UpdateError("не понял версию релиза")
        return parts.getOrElse(0) { 0 } * 1_000_000 + parts.getOrElse(1) { 0 } * 1_000 + parts.getOrElse(2) { 0 }
    }

    fun isNewer(info: Info, currentCode: Long, sdk: Int = Build.VERSION.SDK_INT): Boolean = info.versionCode > currentCode && sdk >= info.minSdk

    fun shouldAutoCheck(enabled: Boolean, auto: Boolean, lastMs: Long, nowMs: Long, unmetered: Boolean, wifiOnly: Boolean) =
        enabled && auto && nowMs - lastMs >= DAY_MS && (unmetered || !wifiOnly)

    /** Страница Google Drive «не удалось проверить на вирусы»: собираем ссылку из формы и повторяем. */
    fun driveConfirmUrl(html: String, original: String): String? {
        val form = Regex("(?is)<form[^>]*id=\"download-form\"[^>]*action=\"([^\"]+)\"[^>]*>(.*?)</form>").find(html)
        if (form != null) {
            val action = form.groupValues[1].replace("&amp;", "&")
            val params = Regex("(?is)<input[^>]*type=\"hidden\"[^>]*name=\"([^\"]+)\"[^>]*value=\"([^\"]*)\"").findAll(form.groupValues[2])
                .joinToString("&") { it.groupValues[1] + "=" + URLEncoder.encode(it.groupValues[2].replace("&amp;", "&"), "UTF-8") }
            if (params.isNotEmpty()) return action + (if (action.contains('?')) "&" else "?") + params
        }
        val token = Regex("confirm=([0-9A-Za-z_-]+)").find(html)?.groupValues?.get(1) ?: return null
        val id = Regex("[?&]id=([0-9A-Za-z_-]+)").find(original)?.groupValues?.get(1) ?: return null
        val uuid = Regex("name=\"uuid\" value=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
        return "https://drive.usercontent.google.com/download?id=$id&export=download&confirm=$token" + (uuid?.let { "&uuid=$it" } ?: "")
    }

    // ---------- состояние

    fun prefs(c: Context) = c.getSharedPreferences(P, 0)
    fun autoCheck(c: Context) = prefs(c).getBoolean("auto", true)
    fun setAutoCheck(c: Context, v: Boolean) = prefs(c).edit().putBoolean("auto", v).apply()
    fun wifiOnly(c: Context) = prefs(c).getBoolean("wifi_only", true)
    fun setWifiOnly(c: Context, v: Boolean) = prefs(c).edit().putBoolean("wifi_only", v).apply()
    fun lastCheck(c: Context) = prefs(c).getLong("last", 0)
    fun markChecked(c: Context) = prefs(c).edit().putLong("last", System.currentTimeMillis()).apply()
    fun skipped(c: Context) = prefs(c).getLong("skip", 0)
    fun skip(c: Context, code: Long) = prefs(c).edit().putLong("skip", code).apply()

    fun unmetered(c: Context): Boolean = try {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    } catch (_: Exception) { false }

    fun currentCode(c: Context): Long = installedInfo(c, 0).let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() }

    private fun installedInfo(c: Context, flags: Int): PackageInfo = c.packageManager.getPackageInfo(c.packageName, flags)

    // ---------- сеть (только в фоновом потоке)

    init { if (java.net.CookieHandler.getDefault() == null) java.net.CookieHandler.setDefault(java.net.CookieManager()) }

    private fun open(url: String, range: Long = 0): HttpURLConnection {
        var u = url
        repeat(6) {   // ручные редиректы: http→https и между доменами Google
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 30000; c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "PocketAgent/${BuildConfig.VERSION_NAME} (Android)")
            if (range > 0) c.setRequestProperty("Range", "bytes=$range-")
            val code = c.responseCode
            if (code in 300..399) { val loc = c.getHeaderField("Location") ?: throw UpdateError("сервер обновлений: пустой редирект"); c.disconnect(); u = URL(URL(u), loc).toString(); return@repeat }
            return c
        }
        throw UpdateError("слишком много перенаправлений")
    }

    fun fetchManifest(url: String = url()): Info {
        if (url.isBlank()) throw UpdateError("источник обновлений не настроен")
        val c = open(url)
        try {
            if (c.responseCode != 200) throw UpdateError("сервер обновлений ответил ${c.responseCode}")
            val text = c.inputStream.bufferedReader().use { it.readText() }
            return parse(text)
        } finally { c.disconnect() }
    }

    fun dir(c: Context) = File(c.cacheDir, "updates").apply { mkdirs() }

    /** Скачивание с докачкой (.part + Range). Возвращает готовый файл. */
    fun download(c: Context, info: Info, progress: (Long, Long) -> Unit, cancelled: () -> Boolean): File {
        val out = File(dir(c), "PocketAgent-${info.versionCode}.apk")
        if (out.exists() && sha256(out) == info.sha256) return out
        dir(c).listFiles()?.filter { !it.name.startsWith("PocketAgent-${info.versionCode}.") }?.forEach { it.delete() }   // старые версии
        val part = File(dir(c), "PocketAgent-${info.versionCode}.part")
        var url = info.apkUrl
        var attempt = 0
        while (true) {
            attempt++
            val have = if (part.exists()) part.length() else 0L
            val conn = open(url, have)
            try {
                val code = conn.responseCode
                val type = conn.contentType ?: ""
                if (code == 416) { part.delete(); if (attempt < 4) continue else throw UpdateError("не удалось докачать файл") }
                if (code != 200 && code != 206) throw UpdateError("сервер ответил $code")
                if (type.startsWith("text/html")) {
                    val html = conn.inputStream.bufferedReader().use { it.readText() }
                    val next = driveConfirmUrl(html, url)
                    if (next == null || attempt > 3) throw UpdateError("Google Диск не отдал файл (возможно, превышен лимит скачиваний) — попробуйте позже")
                    url = next; continue
                }
                val append = code == 206 && have > 0
                val total = (conn.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
                    ?: conn.contentLengthLong.takeIf { it > 0 }?.let { if (append) it + have else it } ?: info.size)
                var done = if (append) have else 0L
                RandomAccessFile(part, "rw").use { raf ->
                    if (!append) raf.setLength(0)
                    raf.seek(done)
                    conn.inputStream.use { ins ->
                        val buf = ByteArray(64 * 1024)
                        var last = 0L
                        while (true) {
                            if (cancelled()) throw UpdateError("отменено")
                            val n = ins.read(buf); if (n < 0) break
                            raf.write(buf, 0, n); done += n
                            if (done - last > 256 * 1024) { progress(done, total); last = done }
                        }
                    }
                }
                progress(done, total)
                if (total > 0 && done < total) { if (attempt < 6) continue else throw UpdateError("загрузка прервалась") }
                if (!part.renameTo(out)) throw UpdateError("не удалось сохранить файл")
                return out
            } catch (e: java.io.IOException) {
                if (attempt >= 6 || cancelled()) throw UpdateError("ошибка сети: ${e.message}")
                Thread.sleep(1500L * attempt)   // докачаем с места обрыва
            } finally { conn.disconnect() }
        }
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins -> val b = ByteArray(1 shl 16); while (true) { val n = ins.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun certDigests(pi: PackageInfo?): Set<String> {
        if (pi == null) return emptySet()
        val md = { b: ByteArray -> MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) } }
        val sigs = if (Build.VERSION.SDK_INT >= 28 && pi.signingInfo != null) {
            val si = pi.signingInfo!!
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else @Suppress("DEPRECATION") pi.signatures
        return sigs?.map { md(it.toByteArray()) }?.toSet() ?: emptySet()
    }

    /** null — файл можно ставить; иначе — причина отказа. */
    fun verify(c: Context, f: File, info: Info): String? {
        val sum = sha256(f)
        if (sum != info.sha256.lowercase()) { f.delete(); return "контрольная сумма не совпала — файл повреждён или подменён" }
        val pm = c.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        var arch = pm.getPackageArchiveInfo(f.absolutePath, flags) ?: return "файл не является приложением Android"
        if (arch.packageName != c.packageName) return "это другое приложение (${arch.packageName})"
        var theirs = certDigests(arch)
        @Suppress("DEPRECATION")
        if (theirs.isEmpty() && Build.VERSION.SDK_INT >= 28) {   // на части версий Android signingInfo архива пуст
            arch = pm.getPackageArchiveInfo(f.absolutePath, PackageManager.GET_SIGNATURES) ?: arch
            theirs = certDigests(arch)
        }
        val mine = certDigests(installedInfo(c, flags))
        if (theirs.isEmpty() || mine.isEmpty() || theirs.intersect(mine).isEmpty()) return "подпись обновления не совпадает с установленным приложением"
        val code = if (Build.VERSION.SDK_INT >= 28) arch.longVersionCode else @Suppress("DEPRECATION") arch.versionCode.toLong()
        if (code <= currentCode(c)) return "в файле не более новая версия"
        return null
    }
}
