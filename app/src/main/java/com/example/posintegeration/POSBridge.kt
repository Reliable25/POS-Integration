package com.example.posintegeration

import android.content.Intent
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import com.google.zxing.integration.android.IntentIntegrator

class POSBridge(private val activity: MainActivity) {

    @JavascriptInterface
    fun startPayment(amount: Int) {
        activity.runOnUiThread {
            activity.startK11Payment(amount)
        }
    }

    @JavascriptInterface
    fun printReceipt(base64Image: String) {
        activity.runOnUiThread {
            activity.printFromWeb(base64Image)
        }
    }

    @JavascriptInterface
    fun openCamera() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        activity.startActivityForResult(intent, MainActivity.CAMERA_REQUEST)
    }

    @JavascriptInterface
    fun scanCode() {
        val integrator = IntentIntegrator(activity)
        integrator.setDesiredBarcodeFormats(IntentIntegrator.ALL_CODE_TYPES)
        integrator.setPrompt("Scan code")
        integrator.setBeepEnabled(true)
        integrator.initiateScan()
    }
}