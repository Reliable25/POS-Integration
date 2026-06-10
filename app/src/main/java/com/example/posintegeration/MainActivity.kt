package com.example.posintegeration
import com.google.gson.Gson
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
        Log.d(TAG, "CALLBACK RECEIVED => ${response.transactionState}")
        runOnUiThread {
            val data = response.transactionData
            val processCode = data?.processCode
            val processMessage = data?.processMessage ?: response.transactionState.name

            val isSuccess = response.transactionState == TransactionState.TRANSACTION_SUCCESSFUL &&
                    (processCode == "00" || processCode == "0")

            Log.d(TAG, "STATE: ${response.transactionState}, CODE: $processCode, MESSAGE: $processMessage")

            when (response.transactionState) {
                TransactionState.INSERT_CARD -> {
                    Toast.makeText(this, "Please insert your card", Toast.LENGTH_SHORT).show()
                }

                TransactionState.PROCESSING -> {
                    Toast.makeText(this, "Processing transaction, please wait...", Toast.LENGTH_SHORT).show()
                }

                TransactionState.TRANSACTION_SUCCESSFUL -> {
                    if (isSuccess) {
                        // Success - show success toast
                        Toast.makeText(
                            this,
                            "✅ Payment Successful: ₦${data?.amount}\nRRN: ${data?.rrn}",
                            Toast.LENGTH_LONG
                        ).show()

                        Log.d(TAG, "========== TRANSACTION SUCCESS ==========")
                        Log.d(TAG, "Amount: ${data?.amount}")
                        Log.d(TAG, "RRN: ${data?.rrn}")
                        Log.d(TAG, "STAN: ${data?.stan}")
                        Log.d(TAG, "Auth Code: ${data?.authCode}")
                        Log.d(TAG, "Terminal ID: ${data?.terminalId}")
                        Log.d(TAG, "Merchant ID: ${data?.merchantId}")
                        Log.d(TAG, "Card Number: ${data?.cardNumber}")
                        Log.d(TAG, "Card Holder: ${data?.cardHolderName}")
                        Log.d(TAG, "Date Time: ${data?.dateTime}")
                        Log.d(TAG, "Process Code: ${data?.processCode}")
                        Log.d(TAG, "Process Message: ${data?.processMessage}")
                        Log.d(TAG, "========================================")

                        notifyAngular("SUCCESS", data)
                    } else {
                        // Transaction returned success state but with error code
                        val errorMessage = when (processCode) {
                            "01" -> "Card declined by bank"
                            "05" -> "Transaction declined - Do not honor"
                            "14" -> "Invalid card number"
                            "51" -> "Insufficient funds"
                            "54" -> "Expired card"
                            "55" -> "Incorrect PIN"
                            "57" -> "Transaction not permitted to cardholder"
                            "61" -> "Exceeds withdrawal limit"
                            "65" -> "Exceeds withdrawal frequency limit"
                            "91" -> "Issuer unavailable"
                            else -> processMessage ?: "Transaction failed with code: $processCode"
                        }

                        Toast.makeText(this, "❌ Payment Failed: $errorMessage", Toast.LENGTH_LONG).show()
                        Log.d(TAG, "FAILED => State=${response.transactionState}, Code=$processCode, Message=$errorMessage")
                        notifyAngular("FAILED", data)
                    }
                }

                TransactionState.TRANSACTION_NOT_SUCCESSFUL -> {
                    // Transaction was processed but not successful (e.g., declined)
                    val errorMsg = when {
                        processCode == "55" -> "Incorrect PIN. Please try again."
                        processCode == "51" -> "Insufficient funds on card"
                        processCode == "54" -> "Card has expired"
                        processCode == "14" -> "Invalid card number"
                        processCode == "01" -> "Card declined by issuing bank"
                        else -> processMessage ?: "Transaction declined"
                    }

                    Toast.makeText(this, "❌ $errorMsg", Toast.LENGTH_LONG).show()
                    notifyAngular("FAILED", data)
                }

                TransactionState.FAILED -> {
                    // Transaction failed due to error
                    val errorMsg = when {
                        processCode == "55" -> "Incorrect PIN. Please try again."
                        processCode == "51" -> "Insufficient funds"
                        processCode == "91" -> "Bank network error. Please try again."
                        else -> processMessage ?: "Transaction failed. Please try again."
                    }

                    Toast.makeText(this, "❌ Transaction Failed: $errorMsg", Toast.LENGTH_LONG).show()
                    notifyAngular("FAILED", data)
                }

                else -> {
                    Log.d(
                        TAG,
                        """
                         =========================
                            STATE: ${response.transactionState}
                            DATA: $data
                            =========================
                     """.trimIndent()
                    )
                    // Show toast for any other states (like DONE_PROCESSING, etc.)
                    if (response.transactionState.name != "DONE_PROCESSING") {
                        Toast.makeText(this, "Status: ${response.transactionState}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun notifyAngular(status: String, data: Any?) {

        val jsonData = Gson().toJson(mapOf(
            "status" to status,
            "data" to data
        ))

        webView.post {
            webView.evaluateJavascript(
                """
            if (window.onPosPaymentResult) {
                window.onPosPaymentResult($jsonData);
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
        Log.d(TAG, "printFromWeb called with Base64 length: ${base64Image.length}")
        Log.d(TAG, "Base64 preview: ${base64Image.take(100)}")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val cleanBase64 = base64Image
                    .replace("data:image/png;base64,", "")
                    .replace("\n", "")

                val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap == null) {
                    Log.e(TAG, "Bitmap is null - invalid image data")
                    Log.e(TAG, "First 100 bytes: ${bytes.take(100).joinToString()}")
                    return@launch
                }

                Log.d(TAG, "Bitmap created successfully: ${bitmap.width}x${bitmap.height}")
                withContext(Dispatchers.Main) {
                    remitaK11.print(img = bitmap) {
                        Log.d(TAG, "Print result: $it")
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