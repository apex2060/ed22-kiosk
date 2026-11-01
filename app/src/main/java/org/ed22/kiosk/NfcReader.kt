package org.ed22.kiosk

import android.app.Activity
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.Charset

/**
 * NFC tag reading for `kiosk.nfc.*`. Uses reader mode, which only works while the activity is in
 * the foreground; while it is on, Android does not hand tags to other apps (no browser pop-ups).
 */
class NfcReader(private val activity: Activity, private val onTag: (JSONObject) -> Unit) {
    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    private var wanted = false
    private var resumed = false

    fun status(): JSONObject = JSONObject()
        .put("available", adapter != null)
        .put("enabled", wanted && adapter != null)
        .put("systemEnabled", adapter?.isEnabled == true)

    fun enable(): JSONObject {
        wanted = true
        apply()
        return status()
    }

    fun disable(): JSONObject {
        wanted = false
        apply()
        return status()
    }

    fun onResume() {
        resumed = true
        apply()
    }

    fun onPause() {
        // Reader mode must be turned off before the activity pauses.
        resumed = false
        apply()
    }

    private fun apply() {
        val nfc = adapter ?: return
        if (wanted && resumed) {
            val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_NFC_BARCODE
            nfc.enableReaderMode(activity, { tag -> onTag(describe(tag)) }, flags, null)
        } else {
            try {
                nfc.disableReaderMode(activity)
            } catch (e: Exception) {
                // Activity not resumed; reader mode is already off.
            }
        }
    }

    private fun describe(tag: Tag): JSONObject {
        val records = JSONArray()
        var url: String? = null
        val message = try {
            Ndef.get(tag)?.cachedNdefMessage
        } catch (e: Exception) {
            null
        }
        message?.records?.forEach { r ->
            val json = record(r)
            records.put(json)
            if (url == null) url = json.optString("url").takeIf { it.isNotEmpty() }
        }
        return JSONObject()
            .put("id", tag.id.joinToString("") { "%02X".format(it) })
            .put("url", url ?: JSONObject.NULL)
            .put("records", records)
            .put("techs", JSONArray(tag.techList.map { it.substringAfterLast('.') }))
    }

    private fun record(r: NdefRecord): JSONObject {
        val json = JSONObject().put("tnf", r.tnf.toInt())
        val typeText = String(r.type, Charsets.US_ASCII)
        fun text(payload: String) = json.put("payload", payload).put("encoding", "utf8")
        fun binary() = json.put("payload", Base64.encodeToString(r.payload, Base64.NO_WRAP)).put("encoding", "base64")
        when {
            r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_URI) ||
                r.tnf == NdefRecord.TNF_ABSOLUTE_URI -> {
                val uri = r.toUri()?.toString() ?: typeText
                json.put("type", "uri").put("url", uri)
                text(uri)
            }
            r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_TEXT) -> {
                val p = r.payload
                if (p.isEmpty()) return json.put("type", "text").put("payload", "")
                val status = p[0].toInt()
                val langLength = status and 0x3F
                val charset = if (status and 0x80 != 0) Charsets.UTF_16 else Charsets.UTF_8
                json.put("type", "text").put("lang", String(p, 1, langLength, Charsets.US_ASCII))
                text(String(p, 1 + langLength, p.size - 1 - langLength, charset))
            }
            r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_SMART_POSTER) -> {
                json.put("type", "smartposter")
                val inner = try {
                    NdefMessage(r.payload).records.map(::record)
                } catch (e: Exception) {
                    emptyList()
                }
                inner.firstOrNull { it.has("url") }?.let { json.put("url", it.getString("url")) }
                json.put("records", JSONArray(inner))
            }
            r.tnf == NdefRecord.TNF_MIME_MEDIA -> {
                json.put("type", "mime").put("mimeType", typeText)
                if (typeText.startsWith("text/") || typeText.contains("json") || typeText.contains("xml")) {
                    text(String(r.payload, Charset.forName("UTF-8")))
                } else {
                    binary()
                }
            }
            r.tnf == NdefRecord.TNF_EXTERNAL_TYPE -> {
                json.put("type", "external").put("recordType", typeText)
                text(String(r.payload, Charsets.UTF_8))
            }
            r.tnf == NdefRecord.TNF_EMPTY -> json.put("type", "empty")
            else -> {
                json.put("type", if (r.tnf == NdefRecord.TNF_WELL_KNOWN) "well_known" else "unknown")
                    .put("recordType", typeText)
                binary()
            }
        }
        return json
    }
}
