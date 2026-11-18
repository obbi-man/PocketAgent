package ru.pocketagent

import android.content.Context
import java.io.InputStream

/** Текст из PDF (PdfBox-Android). Отдельный объект — библиотека загружается только при первом PDF. */
object PdfText {
    const val MAX_PAGES = 150
    @Volatile private var inited = false

    fun extract(ctx: Context, input: InputStream): String {
        if (!inited) { com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(ctx.applicationContext); inited = true }
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(input).use { doc ->
            if (doc.isEncrypted) try { doc.setAllSecurityToBeRemoved(true) } catch (_: Throwable) {}
            val st = com.tom_roush.pdfbox.text.PDFTextStripper()
            st.sortByPosition = true
            st.startPage = 1; st.endPage = minOf(doc.numberOfPages, MAX_PAGES)
            val text = st.getText(doc)
            val more = if (doc.numberOfPages > MAX_PAGES) "\n[прочитаны первые $MAX_PAGES страниц из ${doc.numberOfPages}]" else ""
            return (text.take(DocText.MAX_CHARS) + more).trim()
        }
    }
}
