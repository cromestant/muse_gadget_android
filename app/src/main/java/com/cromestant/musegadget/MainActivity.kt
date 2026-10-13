package com.cromestant.musegadget

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** Stub activity. The real deliverable of this chunk is the crypto modules. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "MuseGadget" })
    }
}
