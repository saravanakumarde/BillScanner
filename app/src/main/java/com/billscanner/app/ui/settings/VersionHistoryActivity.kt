package com.billscanner.app.ui.settings

import android.os.Bundle
import android.text.Html
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.billscanner.app.R

/**
 * A curated, human-written changelog — not generated from the installed
 * app's versionCode (that number comes from the CI build run and wouldn't
 * mean anything to the user). Update [R.string.version_history_content]
 * when shipping a new version.
 */
class VersionHistoryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_version_history)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val html = getString(R.string.version_history_content)
        findViewById<TextView>(R.id.tvVersionHistory).text =
            Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
    }
}
