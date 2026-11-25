package org.ed22.kiosk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** House of Learning device: local toggle, pairing path, policy overlay, no screenshots, studentId only. */
class HolDevicePlanTest {
    private val service = "S98UaXvYQPw0YXalK7MD"
    private val device = "18ced952901c68c1"
    private val path = "acl/db/groups/$service/share/hol/explorer/$device"

    @Test
    fun pairingCodeIsEightCharactersUppercase() {
        assertEquals("AB23CDEF", HolDevicePlan.sanitizeCode("ab23cdef"))
        assertEquals("AB23CDEF", HolDevicePlan.sanitizeCode(" ab-23-cdef "))
        assertEquals("ABCD2345", HolDevicePlan.sanitizeCode("abcd2345extra"))
        assertFalse(HolDevicePlan.codeReady(HolDevicePlan.sanitizeCode("SHORT")))
        assertTrue(HolDevicePlan.codeReady("ABCD2345"))
        assertEquals(8, HolDevicePlan.CODE_LEN)
    }

    @Test
    fun explorerPathIsTheGroupShareHolExplorerDoc() {
        assertEquals(path, HolDevicePlan.pathOf(service, device))
        assertTrue(HolDevicePlan.isExplorerPath(path))
        assertEquals(service, HolDevicePlan.serviceIdOf(path))
        assertEquals("hol-device-$device", HolDevicePlan.uidOf(device))
        assertTrue(HolDevicePlan.isHolDeviceUid("hol-device-$device"))
        assertFalse(HolDevicePlan.isHolDeviceUid("family-uid"))
        assertFalse(HolDevicePlan.isHolDeviceUid(null))
        // Never the Tuya / admin path.
        assertFalse(HolDevicePlan.isExplorerPath("acl/db/groups/$service/share/admin/holExplorer/$device"))
        assertFalse(HolDevicePlan.isExplorerPath("share/library/myItems/exp_x_$device"))
        assertFalse(HolDevicePlan.isExplorerPath("acl/db/groups/ab/share/hol/explorer/$device")) // serviceId too short
        assertEquals("acl/db/groups/$service/share/hol/explorerAudit", HolDevicePlan.auditCollection(service))
    }

    @Test
    fun localToggleIsTheSourceOfTruthForPolicyHol() {
        val empty = HolDevicePlan.applyLocalHol(JSONObject(), on = true, serviceId = service, warnMin = 5)
        val hol = empty.getJSONObject("hol")
        assertTrue(hol.getBoolean("on"))
        assertEquals(service, hol.getString("serviceId"))
        assertEquals(5, hol.getInt("warnMin"))
        // Even if a leftover personal doc still has policy.hol.on, the local Off flag wins.
        val doc = JSONObject().put("hol", JSONObject().put("on", true).put("warnMin", 9))
        val off = HolDevicePlan.applyLocalHol(doc, on = false, serviceId = null)
        assertFalse(off.getJSONObject("hol").getBoolean("on"))
        assertFalse(off.getJSONObject("hol").has("serviceId"))
        // Signed out, no personal account: policy.hol.on is still present for kiosk.getAuth().
        val signedOut = HolDevicePlan.policyHol(on = true, serviceId = null)
        assertTrue(signedOut.getBoolean("on"))
        assertFalse(signedOut.has("serviceId"))
    }

    @Test
    fun setLocalPolicyIsAllowedInHolModeWithoutAPersonalAccount() {
        assertTrue(HolDevicePlan.canSetLocalPolicy(signedIn = true, holOn = false))
        assertTrue(HolDevicePlan.canSetLocalPolicy(signedIn = false, holOn = true))
        assertTrue(HolDevicePlan.canSetLocalPolicy(signedIn = true, holOn = true))
        assertFalse(HolDevicePlan.canSetLocalPolicy(signedIn = false, holOn = false))
    }

    @Test
    fun screenshotsAreSkippedWhileHolModeIsOn() {
        assertTrue(HolDevicePlan.allowScreenshot(holOn = false))
        assertFalse(HolDevicePlan.allowScreenshot(holOn = true))
    }

    @Test
    fun explorerReportFieldsAndAuditUseStudentFacingNamesOnly() {
        assertEquals(
            setOf("online", "appVersion", "battery", "visiting", "name", "current", "activeStudentId", "last", "updatedOn"),
            HolDevicePlan.EXPLORER_FIELDS,
        )
        assertFalse("screenshot" in HolDevicePlan.EXPLORER_FIELDS)
        assertFalse("status.screenshot" in HolDevicePlan.EXPLORER_FIELDS)
        assertFalse(HolDevicePlan.EXPLORER_FIELDS.any { it.contains("child", ignoreCase = true) })
        val detail = HolDevicePlan.auditDetail(device, "HOL-1", "Ava", mapOf("id" to "t1", "title" to "Math"))
        assertEquals(device, detail["deviceId"])
        assertEquals("HOL-1", detail["title"])
        assertEquals("Ava", detail["name"])
        assertEquals(mapOf("id" to "t1", "title" to "Math"), detail["current"])
        assertFalse(detail.containsKey("child" + "Id"))
        assertFalse(detail.containsKey("screenshot"))
        assertEquals("explorer.visitStart", HolDevicePlan.AUDIT_START)
        assertEquals("explorer.visitEnd", HolDevicePlan.AUDIT_END)
        assertTrue(HolDevicePlan.isHolLogout(JSONObject().put("type", "holLogout")))
        assertFalse(HolDevicePlan.isHolLogout(JSONObject().put("type", "screenshot")))
    }
}
