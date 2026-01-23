package com.example.posintegeration

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.systemspecs.remita.processor.device.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Base64
import com.google.zxing.integration.android.IntentIntegrator
import java.io.ByteArrayOutputStream

class MainActivity : FragmentActivity(), RemitaCardTransactionListener {

    private lateinit var remitaK11: RemitaK11
    private lateinit var webView: WebView
    private lateinit var posBridge: POSBridge

    companion object {
        const val CAMERA_REQUEST = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false

        webView.webViewClient = object : android.webkit.WebViewClient() {
            @RequiresApi(Build.VERSION_CODES.O)
            override fun onRenderProcessGone(
                view: WebView,
                detail: android.webkit.RenderProcessGoneDetail
            ): Boolean {
                Log.e("WebView", "Renderer crashed, didCrash=${detail.didCrash()}")
                view.destroy()
                return true // prevent app kill
            }
        }

        posBridge = POSBridge(this)
        webView.addJavascriptInterface(posBridge, "K11POS")

        webView.loadUrl("https://revsyweb.testietech.com.ng")

        remitaK11 = RemitaK11(
            this,
            EnvType.Test,
            apiKey = "sk_test_w1ej15bfw1ctcM/taMkU7ruXaRbo81iWp/78iaj4gchj9MQXhrCsbv3H0dMlxwfO"
        )
        remitaK11.setRemitaCardTransactionListener(this)
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        }
        super.onDestroy()
    }

    fun startK11Payment(amount: Int) {
        lifecycleScope.launch {
            val transactionRef = "POS-${System.currentTimeMillis()}"
            remitaK11.processCardTransaction(
                amountInKobo = amount * 100L,
                transRef = transactionRef,
                currencyCode = "NGN",
                transactionType = TransactionType.purchase,
                transactionDescription = "POS Purchase"
            )
        }
    }

    fun mockK11Payment(amount: Int) {
        webView.postDelayed({
            val mockResponse = CardTransactionResponse(
                transactionState = TransactionState.TRANSACTION_SUCCESSFUL,
                message = "Mock transaction successful",
                transactionData = null
            )
            onRemitaCardTransactionResponse(mockResponse)
        }, 2000)
    }

    override fun onRemitaCardTransactionResponse(response: CardTransactionResponse) {
        runOnUiThread {
            when (response.transactionState) {
                TransactionState.INSERT_CARD ->
                    Toast.makeText(this, "Please insert your card", Toast.LENGTH_SHORT).show()

                TransactionState.TRANSACTION_SUCCESSFUL ->
                    Toast.makeText(this, "Transaction successful", Toast.LENGTH_SHORT).show()

                TransactionState.FAILED ->
                    Toast.makeText(this, "Transaction failed", Toast.LENGTH_SHORT).show()

                else ->
                    Toast.makeText(this, "Transaction state: ${response.transactionState}", Toast.LENGTH_SHORT).show()
            }
            print(response)
        }
    }

    fun printFromWeb(base64Image: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val cleanBase64 = base64Image
                    .replace("data:image/png;base64,", "")
                    .replace("\n", "")

                val decodedBytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)

                withContext(Dispatchers.Main) {
                    remitaK11.print(img = bitmap) {
                        Log.d("POS_PRINT", it)
                    }
                }
            } catch (e: Exception) {
                Log.e("POS_PRINT", "Print failed", e)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {

        val scanResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (scanResult != null && scanResult.contents != null) {
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

