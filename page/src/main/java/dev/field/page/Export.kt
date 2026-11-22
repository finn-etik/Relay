package dev.field.page

import android.content.Context
import android.content.Intent
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient

/** Ways out of PAGE that need no file picker: the share sheet and the print dialog (Save as PDF). */
object Export {

    fun share(context: Context, title: String, text: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .putExtra(Intent.EXTRA_TEXT, text)
        runCatching { context.startActivity(Intent.createChooser(send, null)) }
    }

    /** Kept until the page has loaded; a WebView with no reference may be collected first. */
    private var printing: WebView? = null

    /**
     * Lays the document out as HTML and hands it to the system print dialog.
     * [activity] must be the Activity: printing is not allowed from an app context.
     * JavaScript stays off (the WebView default); the page is our own HTML.
     */
    fun print(activity: Context, title: String, html: String) {
        val name = title.ifEmpty { "Untitled" }
        val web = WebView(activity)
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                runCatching {
                    activity.getSystemService(PrintManager::class.java)
                        .print(name, view.createPrintDocumentAdapter(name), PrintAttributes.Builder().build())
                }
                printing = null
            }
        }
        printing = web
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }
}
