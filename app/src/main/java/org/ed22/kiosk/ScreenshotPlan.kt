package org.ed22.kiosk

import org.json.JSONObject

/**
 * policy.command screenshot: the pure decisions (no Android calls, so JVM tests cover them).
 * The capture itself is in [Screenshot] (own window) and [ShotService] (full screen, Android 11+).
 */
object ScreenshotPlan {
    /** Longest side of the uploaded JPEG, and its quality. */
    const val MAX_SIDE = 1280
    const val JPEG_QUALITY = 70
    /** Android 11 (API 30): AccessibilityService.takeScreenshot. */
    const val FULL_SCREEN_SDK = 30
    const val KIOSK_LABEL = "Little Explorer"
    const val SCOPE_SCREEN = "screen"
    const val SCOPE_APP = "app"

    data class AppRef(val pkg: String, val label: String) {
        fun toMap(): Map<String, Any> = mapOf("package" to pkg, "label" to label)
    }

    /** What to do for one command: capture with [scope] (app = the app in front, or null), or fail with [why]. */
    data class Plan(val scope: String?, val app: AppRef?, val why: String) {
        val ok get() = scope != null
    }

    /**
     * [sdk] Build.VERSION.SDK_INT; [fullReady] the accessibility service is connected; [interactive] the screen is on;
     * [kioskInFront] MainActivity.inForeground; [front] the app the child opened from the kiosk (AppUsage.openPkg + label), if any.
     */
    fun plan(sdk: Int, fullReady: Boolean, interactive: Boolean, kioskInFront: Boolean, front: AppRef?, kioskPkg: String): Plan {
        if (!interactive) return Plan(null, null, "The screen is off")
        val kiosk = AppRef(kioskPkg, KIOSK_LABEL)
        if (sdk >= FULL_SCREEN_SDK && fullReady) {
            // scope 'screen' always names an app: the kiosk, the app opened from it, or (Settings / parent's exit) "Another screen".
            val app = if (kioskInFront) kiosk else front ?: AppRef("", "Another screen")
            return Plan(SCOPE_SCREEN, app, "")
        }
        if (kioskInFront) return Plan(SCOPE_APP, kiosk, "")
        val label = front?.label?.takeIf { it.isNotBlank() } ?: "Another app"
        return Plan(null, null, if (sdk >= FULL_SCREEN_SDK) {
            "$label is open. Turn on Little Explorer in Android Settings › Accessibility to see other apps"
        } else {
            "$label is open; this Android version can only show Little Explorer"
        })
    }

    /** The JPEG size: [w] x [h] scaled down (never up) so the longest side is at most [max]. */
    fun scaled(w: Int, h: Int, max: Int = MAX_SIDE): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return 0 to 0
        val longest = maxOf(w, h)
        if (longest <= max) return w to h
        val f = max.toDouble() / longest
        return maxOf(1, Math.round(w * f).toInt()) to maxOf(1, Math.round(h * f).toInt())
    }

    fun folder(uid: String, deviceDocId: String) = "user/$uid/explorerScreens/$deviceDocId"
    fun tags(deviceDocId: String) = "explorerScreenshot,$deviceDocId"
    fun uploadUrl(cloud: String) = "https://api.cloudinary.com/v1_1/$cloud/image/upload"

    /** The multipart form fields other than `file`. */
    fun formFields(preset: String, uid: String, deviceDocId: String): List<Pair<String, String>> = listOf(
        "upload_preset" to preset,
        "folder" to folder(uid, deviceDocId),
        "tags" to tags(deviceDocId),
    )

    /** `secure_url` of a Cloudinary upload response, or null. */
    fun secureUrl(body: String?): String? =
        runCatching { JSONObject(body ?: "").optString("secure_url") }.getOrNull()?.takeIf { it.startsWith("https://") }

    /** `status.screenshot`: the latest one only (each capture overwrites it). studentId only. */
    fun status(id: String, url: String, at: Long, w: Int, h: Int, scope: String, app: AppRef?, studentId: String?): Map<String, Any?> =
        mapOf("id" to id, "url" to url, "at" to at, "w" to w, "h" to h, "scope" to scope,
            "app" to app?.toMap(), "studentId" to studentId?.takeIf { it.isNotBlank() })

    /** The device log line. */
    fun logLine(scope: String?, why: String) =
        if (scope != null) "Screenshot for the parent ($scope)" else "Screenshot failed: $why"

    /** A multipart/form-data body: [fields], then `file` ([fileName], image/jpeg) = [jpeg]. Built in memory (nothing on disk). */
    fun multipart(boundary: String, fields: List<Pair<String, String>>, fileName: String, jpeg: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(jpeg.size + 1024)
        fun w(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
        for ((k, v) in fields) w("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
        w("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\nContent-Type: image/jpeg\r\n\r\n")
        out.write(jpeg)
        w("\r\n--$boundary--\r\n")
        return out.toByteArray()
    }
}
