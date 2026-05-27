package com.example.desn3010

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import java.util.UUID
import org.json.JSONObject
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dantsu.escposprinter.EscPosPrinter
import com.dantsu.escposprinter.connection.bluetooth.BluetoothPrintersConnections
import com.dantsu.escposprinter.textparser.PrinterTextParserImg

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    private val sppUuid: UUID =
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestBluetoothPermissionIfNeeded()

        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.webViewClient = WebViewClient()

        webView.addJavascriptInterface(
            PrinterBridge(),
            "AndroidPrinter"
        )

        webView.loadUrl("file:///android_asset/main.html")
    }

    private fun requestBluetoothPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissionsToRequest = mutableListOf<String>()

            if (
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }

            if (
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }

            if (permissionsToRequest.isNotEmpty()) {
                ActivityCompat.requestPermissions(
                    this,
                    permissionsToRequest.toTypedArray(),
                    1001
                )
            }
        }
    }

    private fun printRawTextToBluetooth(text: String): Boolean {
        try {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e("Printer", "Missing BLUETOOTH_CONNECT permission")
                return false
            }

            val adapter = BluetoothAdapter.getDefaultAdapter()

            if (adapter == null) {
                Log.e("Printer", "Bluetooth adapter not available")
                return false
            }

            val pairedDevices = adapter.bondedDevices

            pairedDevices.forEach {
                Log.d("Printer", "Paired device: ${it.name} - ${it.address}")
            }

            val printer: BluetoothDevice? = pairedDevices.firstOrNull {
                it.name.contains("POS", ignoreCase = true) ||
                        it.name.contains("Printer", ignoreCase = true) ||
                        it.name.contains("58", ignoreCase = true)
            } ?: pairedDevices.firstOrNull()

            if (printer == null) {
                Log.e("Printer", "No paired Bluetooth printer found")
                return false
            }

            Log.d("Printer", "Trying printer: ${printer.name} - ${printer.address}")

            val socket = printer.createRfcommSocketToServiceRecord(sppUuid)

            socket.connect()

            val output = socket.outputStream

            // ESC @ = initialise printer
            output.write(byteArrayOf(0x1B, 0x40))

            // Centre align
            output.write(byteArrayOf(0x1B, 0x61, 0x01))
            output.write("GRADSHOW TEST\n".toByteArray(Charsets.UTF_8))

            // Left align
            output.write(byteArrayOf(0x1B, 0x61, 0x00))
            output.write("------------------------\n".toByteArray(Charsets.UTF_8))
            output.write(text.toByteArray(Charsets.UTF_8))
            output.write("\n\n\n".toByteArray(Charsets.UTF_8))

            output.flush()

            Thread.sleep(500)

            output.close()
            socket.close()

            Log.d("Printer", "Print test sent successfully")
            return true

        } catch (e: Exception) {
            Log.e("Printer", "Print failed", e)
            return false
        }
    }

    inner class PrinterBridge {
        @JavascriptInterface
        fun testPrint() {
            Thread {
                val success = printRawTextToBluetooth(
                    "Bluetooth ESC/POS print test\nIf you can read this, direct printing works."
                )

                runOnUiThread {
                    if (success) {
                        webView.evaluateJavascript("alert('Test print sent')", null)
                    } else {
                        webView.evaluateJavascript("alert('Print failed. Check Logcat.')", null)
                    }
                }
            }.start()
        }

        @JavascriptInterface
        fun printReceipt(receiptJson: String) {
            Thread {
                try {

                    val success = printEscPosReceipt(receiptJson)

                    runOnUiThread {
                        if (success) {
                            webView.evaluateJavascript("onNativePrintSuccess()", null)
                        } else {
                            webView.evaluateJavascript(
                                "alert('Printing failed. Check printer connection.')",
                                null
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e("PrinterBridge", "Could not format receipt JSON", e)

                    runOnUiThread {
                        webView.evaluateJavascript("alert('Receipt formatting error.')", null)
                    }
                }
            }.start()
        }
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.BLUETOOTH_SCAN
                    ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun printEscPosReceipt(receiptJson: String): Boolean {
        if (!hasBluetoothPermissions()) {
            Log.e("Printer", "Missing Bluetooth permissions")
            requestBluetoothPermissionIfNeeded()
            return false
        }

        return try {
            val connection = BluetoothPrintersConnections.selectFirstPaired()

            if (connection == null) {
                Log.e("Printer", "No paired Bluetooth printer found")
                return false
            }

            val printer = EscPosPrinter(
                connection,
                203,
                48f,
                32
            )

            val formattedReceipt = buildReceiptFromJson(receiptJson, printer)
            printer.printFormattedText(formattedReceipt)

            true
        } catch (e: Exception) {
            Log.e("Printer", "ESC/POS print failed", e)
            false
        }
    }

    private fun buildReceiptFromJson(receiptJson: String, printer: EscPosPrinter): String {
        val json = JSONObject(receiptJson)

        val fullName = json.optString("fullName", "").trim()
        val email = json.optString("email", "").trim()
        val dateDisplay = json.optString("dateDisplay", "").trim()

        val randomInt = (1..5).random()
        var logoDrawable = ContextCompat.getDrawable(this, R.drawable.logos)
        if (randomInt == 1) {
            logoDrawable = ContextCompat.getDrawable(this, R.drawable.logos)
        } else if (randomInt == 2){
            logoDrawable = ContextCompat.getDrawable(this, R.drawable.logo_2)
        } else if (randomInt == 3){
            logoDrawable = ContextCompat.getDrawable(this, R.drawable.logo_3)
        } else if (randomInt == 4){
            logoDrawable = ContextCompat.getDrawable(this, R.drawable.logo_4)
        } else {
            logoDrawable = ContextCompat.getDrawable(this, R.drawable.logo_5)
        }
        val logoHex = PrinterTextParserImg.bitmapToHexadecimalString(printer, logoDrawable)

        return buildString {
            append("\n\n\n")
            append("[C]$dateDisplay\n")
            append("[C]<b><font size='big'>THANK YOU</font></b>\n")
            append("[C]<b><font color='bg-black'>for giving us your data :)</font></b>\n")
            append("[C]--------------------------------\n")
            append("[C]<img>")
            append(logoHex)
            append("</img>\n")
            append("\n")
            append("[L]<b>Email:--------------------</b>\n")
            wrapForReceipt(email, 32).forEach { line ->
                append("[L]$line[R]$89.00\n")
            }
            append("[L]<b>Full Name:----------------</b>\n")
            append("[L]$fullName[R]$74.00\n")
            append("[L]<b>Email & Name Bonus:-------</b>\n")
            append("[R]$100.00\n")

            append("[C]--------------------------------\n")
            append("[R]<b>Total: </b>$263.00\n")
            append("[R]Sales Tax:   $0.00\n")
            append("[C]--------------------------------\n")
            append("[C]<b><font size='tall'>Take control of your data:</font></b>\n")
            append("[C]<qrcode>https://post-human-propaganda.my.canva.site</qrcode>")
            append("\n\n\n")
            append("[C]--------------------------------\n")
            append("[L](this is a work of satire. your data has not been sold.)\n")
        }
    }
    private fun wrapForReceipt(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)

        val lines = mutableListOf<String>()
        var remaining = text

        while (remaining.length > maxChars) {
            lines.add(remaining.substring(0, maxChars))
            remaining = remaining.substring(maxChars)
        }
        if (remaining.isNotEmpty()) {
            lines.add(remaining)
        }
        return lines
    }

}