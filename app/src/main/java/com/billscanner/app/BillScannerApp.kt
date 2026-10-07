package com.billscanner.app

import android.app.Application
import com.billscanner.app.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

class BillScannerApp : Application() {

    val appScope = CoroutineScope(SupervisorJob())

    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this, appScope)
    }
}
