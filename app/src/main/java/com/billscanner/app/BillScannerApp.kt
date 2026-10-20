package com.billscanner.app

import android.app.Application
import com.billscanner.app.data.db.AppDatabase
import com.billscanner.app.util.StoreMemoryEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BillScannerApp : Application() {

    val appScope = CoroutineScope(SupervisorJob())

    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this, appScope)
    }

    override fun onCreate() {
        super.onCreate()
        // Earlier versions saved the same shop again on almost every correction
        // (Muller 4 times, Punjab Asia Shop 3 times, ...). Merge those duplicates
        // once per start-up; it is a no-op when there is nothing to merge.
        appScope.launch(Dispatchers.IO) {
            try {
                val dao = database.storeMemoryDao()
                val plan = StoreMemoryEngine.consolidate(dao.getAll())
                plan.updates.forEach { dao.update(it) }
                plan.deleteIds.forEach { dao.delete(it) }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
