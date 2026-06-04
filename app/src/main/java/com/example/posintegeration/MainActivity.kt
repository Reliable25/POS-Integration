package com.example.posintegeration

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.zxing.integration.android.IntentIntegrator
import com.systemspecs.remita.processor.device.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class MainActivity : FragmentActivity(), RemitaCardTransactionListener {

    private lateinit var remitaK11: RemitaK11
    private lateinit var webView: WebView
    private lateinit var posBridge: POSBridge

    companion object {
        const val CAMERA_REQUEST = 1001
        private const val TAG = "POS_APP"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)

        WebView.setWebContentsDebuggingEnabled(Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
        }

        webView.webViewClient = object : android.webkit.WebViewClient() {
            @RequiresApi(Build.VERSION_CODES.O)
            override fun onRenderProcessGone(
                view: WebView,
                detail: android.webkit.RenderProcessGoneDetail
            ): Boolean {
                Log.e(TAG, "WebView crashed: ${detail.didCrash()}")
                view.destroy()
                return true
            }
        }

        posBridge = POSBridge(this)
        webView.addJavascriptInterface(posBridge, "K11POS")

        webView.loadUrl("https://revsyweb.testietech.com.ng")

        remitaK11 = RemitaK11(
            this,
            EnvType.Live,
            apiKey = "sk_live_FZ4EktzLcEK+Hk34jjMv8f5kbW89Aa93stjFMay+YI3xqxcbXwupdj63F8E73/MC"
        )

        remitaK11.setRemitaCardTransactionListener(this)

        performKeyExchange()
    }

    // -----------------------------
    // KEY EXCHANGE (FIXED)
    // -----------------------------
    private fun performKeyExchange() {
        lifecycleScope.launch {
            try {
                Log.d(TAG, "Starting key exchange")

                val result = remitaK11.keyExchange() // capture result if SDK returns one

                Log.d(TAG, "Key exchange result: $result")

                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Key exchange completed",
                        Toast.LENGTH_SHORT
                    ).show()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Key exchange failed", e)

                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Key exchange failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // -----------------------------
    // PAYMENT START
    // -----------------------------
    fun startK11Payment(amount: Int) {
        lifecycleScope.launch {
            val ref = "POS-${System.currentTimeMillis()}"

            remitaK11.processCardTransaction(
                amountInKobo = amount * 100L,
                transRef = ref,
                currencyCode = "NGN",
                transactionType = TransactionType.purchase,
                transactionDescription = "POS Purchase"
            )
        }
    }

    // -----------------------------
    // RESPONSE HANDLING (FIXED - IMPORTANT)
    // -----------------------------

    override fun onRemitaCardTransactionResponse(response: CardTransactionResponse) {

        runOnUiThread {

            val data = response.transactionData
            val processCode = data?.processCode
            val message = data?.processMessage ?: data?.description ?: response.message

            val isSuccessful =
                response.transactionState == TransactionState.TRANSACTION_SUCCESSFUL &&
                        (processCode == "00" || processCode == "0")

            Log.d(TAG, """
            STATE: ${response.transactionState}
            CODE: $processCode
            MESSAGE: $message
        """.trimIndent())

            when (response.transactionState) {

                TransactionState.INSERT_CARD -> {
                    showToast("Insert card")
                }

                TransactionState.PROCESSING -> {
                    showToast("Processing...")
                }

                TransactionState.TRANSACTION_SUCCESSFUL -> {

                    if (isSuccessful) {
                        showToast("Transaction Successful")
                    } else {
                        showToast("Transaction Failed: $message")
                    }
                }

                else -> {
                    // Ignore intermediate SDK messages like:
                    // "Input Pin", "Done", etc.
                    Log.d(TAG, "Intermediate State: $message")
                }
            }

            val jsState = if (isSuccessful) "SUCCESS" else "FAILED"

            webView.evaluateJavascript(
                """
            if (window.onPosTransaction) {
                window.onPosTransaction('$jsState');
            }
            """.trimIndent(),
                null
            )
        }
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // -----------------------------
    // PRINT
    // -----------------------------
    fun printFromWeb(base64Image: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val cleanBase64 = base64Image
                    .replace("data:image/png;base64,", "")
                    .replace("\n", "")

                val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

                withContext(Dispatchers.Main) {
                    remitaK11.print(img = bitmap) {
                        Log.d(TAG, it)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Print failed", e)
            }
        }
    }

    // -----------------------------
    // SCAN + CAMERA RESULT
    // -----------------------------
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {

        val scanResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (scanResult?.contents != null) {
            webView.evaluateJavascript(
                "window.onCodeScanned('${scanResult.contents}')",
                null
            )
            return
        }

        if (requestCode == CAMERA_REQUEST && resultCode == Activity.RESULT_OK) {
            val bitmap = data?.extras?.get("data") as? Bitmap ?: return

            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)

            val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

            webView.evaluateJavascript(
                "window.onPhotoCaptured('data:image/png;base64,$base64')",
                null
            )
            return
        }

        super.onActivityResult(requestCode, resultCode, data)
    }
}