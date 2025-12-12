package com.example.AvWx

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
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
        }

        // 2. Add the Javascript Interface for saving PDFs
        // This connects "window.Android.savePdf" in HTML to the Kotlin function below
        myWebView.addJavascriptInterface(WebAppInterface(this), "Android")

        // 3. Set WebChromeClient to handle the permission request from HTML
        myWebView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                // This function triggers when the HTML JS tries to use navigator.geolocation

                val perm = Manifest.permission.ACCESS_FINE_LOCATION
                if (ContextCompat.checkSelfPermission(this@MainActivity, perm) == PackageManager.PERMISSION_GRANTED) {
                    // We already have permission, tell the Website "Yes"
                    callback.invoke(origin, true, false)
                } else {
                    // We don't have permission yet.
                    // Tell the website "No" for now, but ask the User for permission via Android dialog
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

        // 4. Load your HTML file from the assets folder
        myWebView.loadUrl("file:///android_asset/index.html")

        // Removed checkLocationPermission() so it doesn't ask on startup
    }

    // Handle the back button so it navigates the browser history instead of closing the app
    override fun onBackPressed() {
        if (myWebView.canGoBack()) {
            myWebView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    // --- PDF SAVING LOGIC ---
    class WebAppInterface(private val context: Context) {

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