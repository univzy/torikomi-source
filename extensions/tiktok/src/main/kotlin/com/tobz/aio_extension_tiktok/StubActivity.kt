package com.tobz.aio_extension_tiktok

import android.app.Activity
import android.os.Bundle

/**
 * Stub activity — required for Android apps but not used by extensions.
 * Extension logic is loaded via ClassLoader, not via Activity.
 */
class StubActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Do nothing — this is just a placeholder
        finish()
    }
}
