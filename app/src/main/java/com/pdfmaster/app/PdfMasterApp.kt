package com.pdfmaster.app

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class PdfMasterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
    }
}
