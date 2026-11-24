package org.ed22.kiosk

/**
 * policy.command screenshot: the pure decisions (no Android calls, so JVM tests cover them).
 * The capture itself is in [Screenshot] (own window) and [ShotService] (full screen, Android 11+).
 */
object ScreenshotPlan {
    /** Longest side of the JPEG at the first step ([STEPS]). */
    const val MAX_SIDE = 1280
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

    /**
     * First-run setup: offer the full-screen screenshots step after the first PIN (not when changing it) on Android 11+,
     * when the service is not on yet ([alreadyOn] = enabled in Settings, or just turned on silently via the adb grant).
     */
    fun offerSetupStep(sdk: Int, changingPin: Boolean, alreadyOn: Boolean) = sdk >= FULL_SCREEN_SDK && !changingPin && !alreadyOn

    /** Firestore's limit is 1 MiB per doc: the data URL stays at or under this many chars (a JPEG of about 512 KB). */
    const val MAX_DATA_CHARS = 700_000
    const val MIME = "image/jpeg"
    const val DATA_PREFIX = "data:image/jpeg;base64,"
    /** The one screenshot doc per device: share/library/myItems/<deviceDocId>/items/screenshot (overwritten each time). */
    const val ITEMS = "items"
    const val SUBDOC_ID = "screenshot"
    const val DOC_REF = "$ITEMS/$SUBDOC_ID"
    /** How long the subdoc write may take before the command is reported as failed. */
    const val WRITE_TIMEOUT_MS = 30_000L

    const val WHY_OFFLINE = "The tablet is offline"
    const val WHY_SEND = "Could not send the screenshot"
    const val WHY_SLOW = "Could not send the screenshot (slow network)"
    const val WHY_TOO_LARGE = "The screenshot is too large"

    /** One JPEG try: longest side [maxSide] px, [quality]. */
    data class Step(val maxSide: Int, val quality: Int)

    /** Tried in order until the data URL fits [MAX_DATA_CHARS]; one step is usually enough (150-350 KB). */
    val STEPS = listOf(
        Step(1280, 70), Step(1280, 60), Step(1280, 50), Step(1280, 40),
        Step(1024, 50), Step(800, 50), Step(640, 50),
    )

    /** Chars of the data URL for a JPEG of [bytes] bytes (base64, no line breaks). */
    fun dataLength(bytes: Int): Int = DATA_PREFIX.length + 4 * ((bytes + 2) / 3)

    fun fits(bytes: Int) = dataLength(bytes) <= MAX_DATA_CHARS

    fun dataUrl(jpeg: ByteArray): String = DATA_PREFIX + java.util.Base64.getEncoder().encodeToString(jpeg)

    /**
     * Encodes with each of [STEPS] until one fits. [encode] returns the JPEG for a step (or throws). Null = still too large
     * at the smallest step ([WHY_TOO_LARGE]).
     */
    fun <T> firstFit(encode: (Step) -> T, size: (T) -> Int): Pair<Step, T>? {
        for (step in STEPS) {
            val out = encode(step)
            if (fits(size(out))) return step to out
        }
        return null
    }

    /** The subdoc (field names fixed; studentId only). [updatedOn] is FieldValue.serverTimestamp() on the device. */
    fun subdoc(id: String, at: Long, w: Int, h: Int, scope: String, app: AppRef?, studentId: String?, jpeg: ByteArray,
               data: String, updatedOn: Any): Map<String, Any?> = mapOf(
        "id" to id, "at" to at, "w" to w, "h" to h, "scope" to scope, "app" to app?.toMap(),
        "studentId" to studentId?.takeIf { it.isNotBlank() }, "mime" to MIME, "bytes" to jpeg.size, "data" to data,
        "updatedOn" to updatedOn,
    )

    /** `status.screenshot`: the latest one only (each capture overwrites it). No url: the picture is in [DOC_REF]. */
    fun status(id: String, at: Long, w: Int, h: Int, scope: String, app: AppRef?, studentId: String?): Map<String, Any?> =
        mapOf("id" to id, "at" to at, "w" to w, "h" to h, "scope" to scope,
            "app" to app?.toMap(), "studentId" to studentId?.takeIf { it.isNotBlank() }, "doc" to DOC_REF)

    /** The device log line, e.g. "Screenshot for the parent (screen, 240 KB)". */
    fun logLine(scope: String?, why: String, bytes: Int = 0) =
        if (scope != null) "Screenshot for the parent ($scope, ${kb(bytes)} KB)" else "Screenshot failed: $why"

    fun kb(bytes: Int) = (bytes + 512) / 1024
}
