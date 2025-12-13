package com.example.AvWx

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.IOException

class MainActivity : Activity() {

    private lateinit var myWebView: WebView
    private val LOCATION_PERMISSION_REQUEST_CODE = 123

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create the WebView programmatically
        myWebView = WebView(this)
        setContentView(myWebView)

        // 1. Configure WebView Settings
        myWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // CRITICAL: Enable Geolocation here
            setGeolocationEnabled(true)
            // Allow file access just in case
            allowFileAccess = true
        }

        // 2. Add the Javascript Interface for saving PDFs and Updating
        // This connects "window.Android.savePdf" and "window.Android.launchUpdate"
        myWebView.addJavascriptInterface(WebAppInterface(this), "Android")

        // 3. Set WebChromeClient to handle the permission request from HTML
        myWebView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                val perm = Manifest.permission.ACCESS_FINE_LOCATION
                if (ContextCompat.checkSelfPermission(this@MainActivity, perm) == PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, false)
                } else {
                    callback.invoke(origin, false, false)
                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(perm),
                        LOCATION_PERMISSION_REQUEST_CODE
                    )
                }
            }
        }

        myWebView.webViewClient = WebViewClient()

        // 4. Handle Standard Downloads (This fixes window.location.href downloads)
        myWebView.setDownloadListener { url, _, _, _, _ ->
            try {
                val intent = Intent(Intent.ACTION_VIEW)
                intent.data = Uri.parse(url)
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open download link", Toast.LENGTH_SHORT).show()
            }
        }

        // 5. Load your HTML file from the assets folder
        myWebView.loadUrl("file:///android_asset/index.html")
    }

    // Handle the back button so it navigates the browser history instead of closing the app
    override fun onBackPressed() {
        if (myWebView.canGoBack()) {
            myWebView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    // --- JAVASCRIPT INTERFACE ---
    class WebAppInterface(private val context: Context) {

        // Function called by "window.Android.launchUpdate(url)"
        @JavascriptInterface
        fun launchUpdate(url: String) {
            try {
                val intent = Intent(Intent.ACTION_VIEW)
                intent.data = Uri.parse(url)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(context, "Error opening update link", Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun savePdf(base64Data: String, filename: String) {
            try {
                // Convert Base64 string to bytes
                val pdfAsBytes = Base64.decode(base64Data, 0)

                // Use MediaStore (Android 10+ standard way to save downloads)
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

                if (uri != null) {
                    resolver.openOutputStream(uri).use { outputStream ->
                        outputStream?.write(pdfAsBytes)
                    }
                    // Show a small popup message
                    Toast.makeText(context, "PDF saved to Downloads", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "Failed to create file", Toast.LENGTH_SHORT).show()
                }

            } catch (e: IOException) {
                e.printStackTrace()
                Toast.makeText(context, "Error saving PDF: " + e.message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}