package com.callanswer.app.audio

import android.view.accessibility.AccessibilityNodeInfo

/** Buttons of the Samsung in-call screen that the App presses through the accessibility service. */
enum class InCallButton(val words: List<String>) {
    SPEAKER(listOf("speaker", "स्पीकर")),
    MERGE(listOf("merge", "मर्ज")),
    SWAP_RESUME(listOf("swap", "resume", "unhold", "स्वैप", "फिर से शुरू")),
}

/**
 * Finds and clicks Samsung in-call buttons. Buttons are matched by text, content description or
 * view id containing one of [InCallButton.words] (the labels used in spikes S3 and S6), so the match
 * survives One UI id changes. [rootProvider] returns the active window root, or null.
 */
class InCallUiAutomator(private val rootProvider: () -> AccessibilityNodeInfo?) {

    /** Clicks [button]. Returns true when a matching node was found and the click was performed. */
    fun click(button: InCallButton): Boolean {
        val root = rootProvider() ?: return false
        if (!isInCallPackage(root.packageName?.toString())) return false
        val node = find(root, button) ?: return false
        return clickNodeOrParent(node)
    }

    fun clickSpeaker() = click(InCallButton.SPEAKER)
    fun clickMerge() = click(InCallButton.MERGE)
    fun clickSwapOrResume() = click(InCallButton.SWAP_RESUME)

    /** True when the Merge button exists and is enabled (the second call was answered). */
    fun isMergeEnabled(): Boolean {
        val root = rootProvider() ?: return false
        if (!isInCallPackage(root.packageName?.toString())) return false
        return find(root, InCallButton.MERGE)?.isEnabled == true
    }

    private fun find(root: AccessibilityNodeInfo, button: InCallButton): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (matches(n, button)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let(stack::add)
        }
        return null
    }

    private fun matches(n: AccessibilityNodeInfo, button: InCallButton): Boolean {
        val text = labelOf(n.text, n.contentDescription, n.viewIdResourceName)
        return matchesLabel(text, button)
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable && n.isEnabled) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = n.parent
        }
        return false
    }

    companion object {
        /** Samsung in-call UI packages (also set in the service config package filter). */
        val PACKAGES = listOf("com.samsung.android.incallui", "com.samsung.android.dialer")

        fun isInCallPackage(pkg: String?): Boolean = pkg != null && pkg in PACKAGES

        fun labelOf(text: CharSequence?, desc: CharSequence?, viewId: String?): String =
            "${text ?: ""} ${desc ?: ""} ${viewId ?: ""}".lowercase()

        fun matchesLabel(label: String, button: InCallButton): Boolean =
            button.words.any { label.contains(it) }
    }
}
