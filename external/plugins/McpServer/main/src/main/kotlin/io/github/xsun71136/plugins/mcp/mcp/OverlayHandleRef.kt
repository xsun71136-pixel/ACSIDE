package io.github.xsun71136.plugins.mcp.mcp

import com.nullij.androidcodestudio.plugins.api.OverlayHandle

/**
 * Holds the live overlay handle of the 输出与调试 console so the server side can
 * close or inspect it. Null when no console is showing.
 */
object OverlayHandleRef {
    @Volatile
    var current: OverlayHandle? = null

    fun isShowing(): Boolean = try {
        current?.isShowing == true
    } catch (t: Throwable) {
        false
    }

    fun dismiss() {
        runCatching { current?.dismiss() }
        current = null
    }
}
