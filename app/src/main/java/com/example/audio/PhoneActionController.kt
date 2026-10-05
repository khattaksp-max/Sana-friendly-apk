package com.example.audio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log

data class ActionResult(
    val executed: Boolean,
    val spokenConfirmation: String,
    val actionType: String? = null
)

class PhoneActionController(private val context: Context) {

    private val TAG = "PhoneActionController"

    /**
     * Inspects text for phone intent triggers and executes if permitted.
     */
    fun evaluateAndExecute(commandText: String): ActionResult? {
        val lower = commandText.trim().lowercase()

        // 1. WhatsApp Intent
        if (lower.contains("open whatsapp") ||
            lower.contains("واٹس ایپ کھولو") ||
            lower.contains("whatsapp kholo") ||
            lower.contains("whatsapp open")
        ) {
            return openWhatsApp()
        }

        // 2. YouTube Intent
        if (lower.contains("open youtube") ||
            lower.contains("یوٹیوب کھولو") ||
            lower.contains("youtube kholo") ||
            lower.contains("youtube open")
        ) {
            return openYouTube()
        }

        // 3. Camera Intent
        if (lower.contains("open camera") ||
            lower.contains("کیمرہ کھولو") ||
            lower.contains("camera kholo") ||
            lower.contains("take a picture") ||
            lower.contains("camera open")
        ) {
            return openCamera()
        }

        // 4. Settings Intent
        if (lower.contains("open settings") ||
            lower.contains("سیٹنگز کھولو") ||
            lower.contains("settings kholo") ||
            lower.contains("settings open")
        ) {
            return openSettings()
        }

        return null
    }

    private fun openWhatsApp(): ActionResult {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage("com.whatsapp")
        return if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(launchIntent)
                ActionResult(
                    executed = true,
                    spokenConfirmation = "Sure, I'm opening WhatsApp for you.",
                    actionType = "WHATSAPP"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed launching WhatsApp", e)
                ActionResult(
                    executed = false,
                    spokenConfirmation = "I tried to open WhatsApp, but the system prevented the launch.",
                    actionType = "WHATSAPP"
                )
            }
        } else {
            ActionResult(
                executed = false,
                spokenConfirmation = "WhatsApp is not installed on this device.",
                actionType = "WHATSAPP"
            )
        }
    }

    private fun openYouTube(): ActionResult {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage("com.google.android.youtube")
        return if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(launchIntent)
                ActionResult(
                    executed = true,
                    spokenConfirmation = "Opening YouTube for you now.",
                    actionType = "YOUTUBE"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error opening YouTube app", e)
                fallbackWebYouTube()
            }
        } else {
            fallbackWebYouTube()
        }
    }

    private fun fallbackWebYouTube(): ActionResult {
        return try {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webIntent)
            ActionResult(
                executed = true,
                spokenConfirmation = "Opening YouTube in your browser.",
                actionType = "YOUTUBE"
            )
        } catch (e: Exception) {
            ActionResult(
                executed = false,
                spokenConfirmation = "Could not open YouTube on this device.",
                actionType = "YOUTUBE"
            )
        }
    }

    private fun openCamera(): ActionResult {
        return try {
            val cameraIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (cameraIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(cameraIntent)
                ActionResult(
                    executed = true,
                    spokenConfirmation = "Opening camera.",
                    actionType = "CAMERA"
                )
            } else {
                ActionResult(
                    executed = false,
                    spokenConfirmation = "Camera app is not accessible.",
                    actionType = "CAMERA"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening camera", e)
            ActionResult(
                executed = false,
                spokenConfirmation = "Could not launch camera.",
                actionType = "CAMERA"
            )
        }
    }

    private fun openSettings(): ActionResult {
        return try {
            val settingsIntent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(settingsIntent)
            ActionResult(
                executed = true,
                spokenConfirmation = "Opening device Settings.",
                actionType = "SETTINGS"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error opening settings", e)
            ActionResult(
                executed = false,
                spokenConfirmation = "Could not open system settings.",
                actionType = "SETTINGS"
            )
        }
    }
}
