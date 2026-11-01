package dev.zaplatin.smsforwarder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Regression guard: the service must come back after a reboot, before the first unlock. */
class ManifestDirectBootTest {

    private val manifest: Element = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(File("src/main/AndroidManifest.xml"))
        .documentElement

    @Test
    fun bootReceiver_isDirectBootAwareAndListensForLockedBoot() {
        val receiver = component("receiver", ".BootReceiver")
        assertEquals("true", receiver.androidAttr("directBootAware"))
        val actions = receiver.actions()
        assertTrue(actions.toString(), "android.intent.action.LOCKED_BOOT_COMPLETED" in actions)
        assertTrue(actions.toString(), "android.intent.action.BOOT_COMPLETED" in actions)
    }

    @Test
    fun forwarderService_isDirectBootAware() {
        assertEquals("true", component("service", ".ForwarderService").androidAttr("directBootAware"))
    }

    @Test
    fun smsReceiver_staysCredentialStorageOnly() {
        // Text SMS are held by the platform until unlock, and the receiver writes to Room (CE storage).
        assertEquals("", component("receiver", ".SmsReceiver").androidAttr("directBootAware"))
    }

    private fun component(tag: String, name: String): Element {
        val nodes = manifest.getElementsByTagName(tag)
        val match = (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .firstOrNull { it.androidAttr("name") == name }
        assertNotNull("<$tag $name> not found", match)
        return match!!
    }

    private fun Element.androidAttr(name: String): String = getAttributeNS(ANDROID_NS, name)

    private fun Element.actions(): Set<String> {
        val nodes = getElementsByTagName("action")
        return (0 until nodes.length).map { (nodes.item(it) as Element).androidAttr("name") }.toSet()
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
