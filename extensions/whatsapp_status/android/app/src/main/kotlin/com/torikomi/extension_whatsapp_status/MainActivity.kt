package com.torikomi.extension_whatsapp_status

import android.app.Activity

/**
 * No-op activity used purely as a discoverability anchor for the main
 * Torikomi app's <queries><intent> filter. The activity is never launched
 * by the user; it carries an intent-filter on
 * `com.torikomi.extension.action.DISCOVERY` so that the package becomes
 * visible to the main app under Android 11+ package-visibility rules.
 */
class MainActivity : Activity()
