package com.billscanner.app.data.db

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface RecurringExpenseDao {

    @Insert
    suspend fun insert(expense: RecurringExpense): Long

    @Update
    suspend fun update(expense: RecurringExpense)

    @Delete
    suspend fun delete(expense: RecurringExpense)

    /** Fixed expenses first, then EMIs ("FIXED" sorts after "EMI", hence DESC), each alphabetical. */
    @Query("SELECT * FROM recurring_expenses ORDER BY kind DESC, name COLLATE NOCASE ASC")
    fun observeAll(): LiveData<List<RecurringExpense>>
}
