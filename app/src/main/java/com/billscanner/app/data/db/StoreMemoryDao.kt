package com.billscanner.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.lifecycle.LiveData

@Dao
interface StoreMemoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: StoreMemory): Long

    @Update
    suspend fun update(memory: StoreMemory)

    @Query("SELECT * FROM store_memory ORDER BY lastUsedMillis DESC")
    suspend fun getAll(): List<StoreMemory>

    @Query("SELECT * FROM store_memory ORDER BY correctedStoreName COLLATE NOCASE ASC")
    fun observeAll(): LiveData<List<StoreMemory>>

    @Query("DELETE FROM store_memory WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM store_memory")
    suspend fun deleteAll()
}
