package com.callanswer.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Fails when the merged manifest (of the variant under test; the release build in `testReleaseUnitTest`)
 * declares RECORD_AUDIO, READ_CALL_LOG, an accessibility service, or any call screening service other than
 * the Screening_Observer (Requirements 24.4, 24.7, 33.13).
 *
 * Why exactly one screening service is allowed: the app holds the call screening role only to read the
 * incoming number for the Ringing_Alert (repeat-caller alert, Requirement 33). `RepeatCallerScreeningService`
 * always answers with the default CallResponse (no reject, silence, or skipped call log/notification). It
 * never touches call audio, so RECORD_AUDIO, READ_CALL_LOG and accessibility services stay forbidden.
 */
class ReleaseManifestTest {
    private val ns = "http://schemas.android.com/apk/res/android"

    private fun manifest() = File(requireNotNull(System.getProperty("callanswer.mergedManifest")) { "merged manifest path not set" })
        .also { assertTrue("merged manifest missing: $it", it.isFile) }
        .let { f ->
            DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(f).documentElement
        }

    private fun names(tag: String): List<String> {
        val nodes = manifest().getElementsByTagName(tag)
        return (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(ns, "name") }
    }

    @Test
    fun noRecordAudioPermission() {
        val all = names("uses-permission") + names("uses-permission-sdk-23")
        assertTrue("RECORD_AUDIO must not be declared: $all", all.none { it == "android.permission.RECORD_AUDIO" })
    }

    @Test
    fun noReadCallLogPermission() {
        val all = names("uses-permission") + names("uses-permission-sdk-23")
        assertTrue("READ_CALL_LOG must not be declared: $all", all.none { it == "android.permission.READ_CALL_LOG" })
    }

    private fun isScreeningObserver(name: String) =
        name.startsWith("com.callanswer.app.") && name.endsWith(".RepeatCallerScreeningService")

    @Test
    fun onlyTheScreeningObserverMayScreenCalls() {
        val screeningAction = "android.telecom.CallScreeningService"
        val screeningPermission = "android.permission.BIND_SCREENING_SERVICE"
        val services = manifest().getElementsByTagName("service")
        val screening = mutableListOf<String>()
        for (i in 0 until services.length) {
            val service = services.item(i) as Element
            val name = service.getAttributeNS(ns, "name")
            val actions = service.getElementsByTagName("action")
            val declared = (0 until actions.length).map { (actions.item(it) as Element).getAttributeNS(ns, "name") }
            val permission = service.getAttributeNS(ns, "permission")
            if (screeningAction in declared || permission == screeningPermission) {
                assertTrue("only RepeatCallerScreeningService may be a screening service, found $name", isScreeningObserver(name))
                assertTrue("$name must declare $screeningAction: $declared", screeningAction in declared)
                assertTrue("$name must require $screeningPermission, found '$permission'", permission == screeningPermission)
                screening += name
            }
        }
        assertTrue("exactly one screening service (the Screening_Observer) expected, found $screening", screening.size == 1)
    }

    @Test
    fun noAccessibilityService() {
        val services = manifest().getElementsByTagName("service")
        for (i in 0 until services.length) {
            val service = services.item(i) as Element
            val name = service.getAttributeNS(ns, "name")
            val actions = service.getElementsByTagName("action")
            val declared = (0 until actions.length).map { (actions.item(it) as Element).getAttributeNS(ns, "name") }
            assertTrue("service $name declares $declared", "android.accessibilityservice.AccessibilityService" !in declared)
            assertTrue(
                "service $name uses BIND_ACCESSIBILITY_SERVICE",
                service.getAttributeNS(ns, "permission") != "android.permission.BIND_ACCESSIBILITY_SERVICE",
            )
        }
    }
}
