package com.billscanner.app.data.db

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

data class CategoryTotal(
    val categoryId: Long,
    val categoryName: String,
    val colorHex: String,
    val total: Double,
    val billCount: Int
)

data class PeriodTotal(
    val periodLabel: String,   // e.g. "2026-W38" or "2026-09"
    val total: Double
)

data class BillWithCategory(
    val id: Long,
    val imageUri: String,
    val storeNameOriginal: String,
    val storeNameEnglish: String,
    val billDateMillis: Long,
    val totalAmount: Double,
    val currency: String,
    val categoryName: String,
    val colorHex: String
)

data class LineItemWithStore(
    val id: Long,
    val nameEnglish: String,
    val nameOriginal: String,
    val totalPrice: Double,
    val currency: String,
    val storeNameEnglish: String,
    val storeNameOriginal: String,
    val billDateMillis: Long,
    val billId: Long
)

@Dao
interface BillDao {

    @Insert
    suspend fun insertBill(bill: Bill): Long

    @Insert
    suspend fun insertLineItems(items: List<LineItem>)

    @Transaction
    suspend fun insertBillWithItems(bill: Bill, items: List<LineItem>): Long {
        val billId = insertBill(bill)
        if (items.isNotEmpty()) {
            insertLineItems(items.map { it.copy(billId = billId) })
        }
        return billId
    }

    @Update
    suspend fun updateBill(bill: Bill)

    @Delete
    suspend fun deleteBill(bill: Bill)

    @Query("SELECT * FROM bills WHERE id = :id")
    suspend fun getBillById(id: Long): Bill?

    @Query("SELECT * FROM line_items WHERE billId = :billId")
    suspend fun getLineItemsForBill(billId: Long): List<LineItem>

    @Query("DELETE FROM line_items WHERE billId = :billId")
    suspend fun deleteLineItemsForBill(billId: Long)

    @Transaction
    suspend fun replaceLineItems(billId: Long, items: List<LineItem>) {
        deleteLineItemsForBill(billId)
        insertLineItems(items.map { it.copy(billId = billId) })
    }

    @Query("SELECT * FROM bills")
    suspend fun getAllBillsSuspend(): List<Bill>

    // ---- List views ----

    @Query(
        """
        SELECT b.id, b.imageUri, b.storeNameOriginal, b.storeNameEnglish,
               b.billDateMillis, b.totalAmount, b.currency,
               c.name AS categoryName, c.colorHex
        FROM bills b LEFT JOIN categories c ON b.categoryId = c.id
        ORDER BY b.billDateMillis DESC
        """
    )
    fun observeAllBills(): LiveData<List<BillWithCategory>>

    @Query(
        """
        SELECT b.id, b.imageUri, b.storeNameOriginal, b.storeNameEnglish,
               b.billDateMillis, b.totalAmount, b.currency,
               c.name AS categoryName, c.colorHex
        FROM bills b LEFT JOIN categories c ON b.categoryId = c.id
        WHERE b.billDateMillis BETWEEN :startMillis AND :endMillis
        ORDER BY b.billDateMillis DESC
        """
    )
    fun observeBillsBetween(startMillis: Long, endMillis: Long): LiveData<List<BillWithCategory>>

    @Query(
        """
        SELECT b.id, b.imageUri, b.storeNameOriginal, b.storeNameEnglish,
               b.billDateMillis, b.totalAmount, b.currency,
               c.name AS categoryName, c.colorHex
        FROM bills b LEFT JOIN categories c ON b.categoryId = c.id
        WHERE b.categoryId = :categoryId
        ORDER BY b.billDateMillis DESC
        """
    )
    fun observeBillsByCategory(categoryId: Long): LiveData<List<BillWithCategory>>

    @Query(
        """
        SELECT b.id, b.imageUri, b.storeNameOriginal, b.storeNameEnglish,
               b.billDateMillis, b.totalAmount, b.currency,
               c.name AS categoryName, c.colorHex
        FROM bills b LEFT JOIN categories c ON b.categoryId = c.id
        WHERE b.storeNameEnglish = :storeName OR b.storeNameOriginal = :storeName
        ORDER BY b.billDateMillis DESC
        """
    )
    fun observeBillsByStore(storeName: String): LiveData<List<BillWithCategory>>

    @Query(
        """
        SELECT DISTINCT storeNameEnglish FROM bills ORDER BY storeNameEnglish ASC
        """
    )
    fun observeDistinctStores(): LiveData<List<String>>

    @Query(
        """
        SELECT DISTINCT storeNameEnglish FROM bills
        WHERE storeNameEnglish != '' ORDER BY storeNameEnglish ASC
        """
    )
    suspend fun getDistinctStoreNames(): List<String>

    // ---- Aggregations for stats screens ----

    @Query(
        """
        SELECT c.id AS categoryId, c.name AS categoryName, c.colorHex,
               COALESCE(SUM(b.totalAmount), 0) AS total,
               COUNT(b.id) AS billCount
        FROM categories c LEFT JOIN bills b
            ON b.categoryId = c.id
            AND b.billDateMillis BETWEEN :startMillis AND :endMillis
        GROUP BY c.id
        ORDER BY total DESC
        """
    )
    fun observeCategoryTotals(startMillis: Long, endMillis: Long): LiveData<List<CategoryTotal>>

    @Query("SELECT COALESCE(SUM(totalAmount), 0) FROM bills WHERE billDateMillis BETWEEN :startMillis AND :endMillis")
    fun observeTotalInRange(startMillis: Long, endMillis: Long): LiveData<Double>

    @Query("SELECT COALESCE(SUM(totalAmount), 0) FROM bills")
    fun observeGrandTotal(): LiveData<Double>

    @Query("SELECT COUNT(*) FROM bills WHERE categoryId = :categoryId")
    suspend fun countBillsInCategory(categoryId: Long): Int

    @Query("UPDATE bills SET categoryId = :newCategoryId WHERE categoryId = :oldCategoryId")
    suspend fun reassignBillsCategory(oldCategoryId: Long, newCategoryId: Long)

    @Query("UPDATE line_items SET categoryId = :newCategoryId WHERE categoryId = :oldCategoryId")
    suspend fun reassignLineItemsCategory(oldCategoryId: Long, newCategoryId: Long)

    @Transaction
    suspend fun reassignCategoryAndDelete(oldCategoryId: Long, newCategoryId: Long) {
        reassignBillsCategory(oldCategoryId, newCategoryId)
        reassignLineItemsCategory(oldCategoryId, newCategoryId)
    }

    @Query("SELECT MIN(billDateMillis) FROM bills")
    suspend fun getEarliestBillDate(): Long?

    // ---- Item price comparison (all items across all bills, A-Z) ----

    @Query(
        """
        SELECT li.id, li.nameEnglish, li.nameOriginal, li.totalPrice, b.currency,
               b.storeNameEnglish, b.storeNameOriginal, b.billDateMillis, b.id AS billId
        FROM line_items li
        JOIN bills b ON li.billId = b.id
        ORDER BY li.nameEnglish COLLATE NOCASE ASC, b.billDateMillis DESC
        """
    )
    fun observeAllLineItemsWithStore(): LiveData<List<LineItemWithStore>>
}
