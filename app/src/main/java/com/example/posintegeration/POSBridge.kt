package com.example.posintegeration

import android.webkit.JavascriptInterface

class POSBridge(private val activity: MainActivity) {

    @JavascriptInterface
    fun startPayment(amount: Int) {
        activity.runOnUiThread {
            activity.startK11Payment(amount)
        }
    }
}