package com.callanswer.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Fails when the merged manifest (of the variant under test; the release build in `testReleaseUnitTest`)
 * declares RECORD_AUDIO, a call screening service, or an accessibility service (Requirements 24.4, 24.7).
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
    fun noCallScreeningOrAccessibilityService() {
        val bad = setOf("android.telecom.CallScreeningService", "android.accessibilityservice.AccessibilityService")
        val services = manifest().getElementsByTagName("service")
        for (i in 0 until services.length) {
            val service = services.item(i) as Element
            val actions = service.getElementsByTagName("action")
            val declared = (0 until actions.length).map { (actions.item(it) as Element).getAttributeNS(ns, "name") }
            assertTrue("service ${service.getAttributeNS(ns, "name")} declares $declared", declared.none { it in bad })
            assertTrue(
                "service ${service.getAttributeNS(ns, "name")} uses BIND_SCREENING_SERVICE / BIND_ACCESSIBILITY_SERVICE",
                service.getAttributeNS(ns, "permission") !in setOf(
                    "android.permission.BIND_SCREENING_SERVICE",
                    "android.permission.BIND_ACCESSIBILITY_SERVICE",
                ),
            )
        }
    }
}
