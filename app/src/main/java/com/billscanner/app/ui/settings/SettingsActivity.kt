package com.billscanner.app.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.billscanner.app.BillScannerApp
import com.billscanner.app.R
import com.billscanner.app.ui.category.ManageCategoriesActivity
import com.billscanner.app.ui.log.ActivityLogActivity
import com.billscanner.app.ui.stores.LearnedStoresActivity
import com.billscanner.app.util.AppPreferences
import com.billscanner.app.util.DataExporter
import com.billscanner.app.util.ScanDetectionMode
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        findViewById<MaterialButton>(R.id.btnManageCategories).setOnClickListener {
            startActivity(Intent(this, ManageCategoriesActivity::class.java))
        }

        val radioGroup = findViewById<RadioGroup>(R.id.radioGroupScanMode)
        val radioAutomatic = findViewById<RadioButton>(R.id.radioAutomatic)
        val radioManual = findViewById<RadioButton>(R.id.radioManual)

        when (AppPreferences.getScanDetectionMode(this)) {
            ScanDetectionMode.AUTOMATIC -> radioAutomatic.isChecked = true
            ScanDetectionMode.MANUAL -> radioManual.isChecked = true
        }

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == R.id.radioManual) {
                ScanDetectionMode.MANUAL
            } else {
                ScanDetectionMode.AUTOMATIC
            }
            AppPreferences.setScanDetectionMode(this, mode)
        }

        val switchDeletePhoto = findViewById<SwitchMaterial>(R.id.switchDeletePhoto)
        switchDeletePhoto.isChecked = AppPreferences.getDeletePhotoOnBillDelete(this)
        switchDeletePhoto.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setDeletePhotoOnBillDelete(this, isChecked)
        }

        findViewById<MaterialButton>(R.id.btnViewLearnedStores).setOnClickListener {
            startActivity(Intent(this, LearnedStoresActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.btnViewActivityLog).setOnClickListener {
            startActivity(Intent(this, ActivityLogActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.btnExportAllData).setOnClickListener {
            exportAllData()
        }
    }

    private val app get() = application as BillScannerApp

    private fun exportAllData() {
        val button = findViewById<MaterialButton>(R.id.btnExportAllData)
        val progress = findViewById<ProgressBar>(R.id.exportProgress)
        button.isEnabled = false
        progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val dao = app.database.billDao()
                    val bills = dao.getAllBillsSuspend()
                    val itemsByBillId = bills.associate { bill ->
                        bill.id to dao.getLineItemsForBill(bill.id)
                    }
                    val categories = app.database.categoryDao().getAll()
                    DataExporter.exportAll(this@SettingsActivity, bills, itemsByBillId, categories)
                }

                progress.visibility = View.GONE
                button.isEnabled = true

                if (result.billCount == 0) {
                    Toast.makeText(this@SettingsActivity, getString(R.string.export_nothing_to_export), Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val message = if (result.skippedPhotos > 0) {
                    getString(
                        R.string.export_done_with_skipped_format,
                        result.billCount, result.imageCount, result.skippedPhotos
                    )
                } else {
                    getString(R.string.export_done_format, result.billCount, result.imageCount)
                }
                Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_LONG).show()

                DataExporter.shareZip(this@SettingsActivity, result.zipFile, getString(R.string.export_share_title))
            } catch (e: Exception) {
                e.printStackTrace()
                progress.visibility = View.GONE
                button.isEnabled = true
                Toast.makeText(this@SettingsActivity, getString(R.string.export_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
