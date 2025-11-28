package com.example.htmlapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import java.io.IOException


class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val webView = WebView(this)
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // Add the JavaScript interface
        val webAppInterface = WebAppInterface(this)
        webView.addJavascriptInterface(webAppInterface, "Android")

        webView.loadUrl("file:///android_asset/index.html")
        setContentView(webView)
    }

    // This class bridges JavaScript calls to native Android code
    class WebAppInterface(private val mContext: Context) {

        @JavascriptInterface
        fun savePdf(base64Data: String, filename: String) {
            saveFile(base64Data, filename)
        }

        // The actual file saving function using MediaStore (Permission-less)
        private fun saveFile(base64Data: String, filename: String) {
            try {
                // Decode the Base64 data string into a byte array
                val pdfAsBytes = Base64.decode(base64Data, Base64.DEFAULT)

                // 💥 Use MediaStore for modern, permission-less saving to Downloads
                val contentResolver = mContext.contentResolver
                val contentValues = android.content.ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    // This tells MediaStore to put it in the public Downloads folder
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                }

                // Insert a new file entry into the MediaStore and get its URI
                val pdfUri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

                if (pdfUri != null) {
                    // Write the bytes to the URI using an Output Stream
                    val os = contentResolver.openOutputStream(pdfUri)
                    os?.use {
                        it.write(pdfAsBytes)
                        it.flush()
                    }

                    // Notify the user
                    Toast.makeText(mContext, "File saved successfully to Downloads: $filename", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(mContext, "Failed to create file entry in MediaStore.", Toast.LENGTH_LONG).show()
                }

            } catch (e: IOException) {
                e.printStackTrace()
                Toast.makeText(mContext, "File saving failed: ${e.message}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(mContext, "An unexpected error occurred: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        @JavascriptInterface
        fun launchUpdate(url: String?) {
            if (url != null && !url.isEmpty()) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                mContext.startActivity(intent)
            }
        }
    }
}