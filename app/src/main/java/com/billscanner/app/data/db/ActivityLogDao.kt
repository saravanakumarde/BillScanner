package com.billscanner.app.data.db

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ActivityLogDao {

    @Insert
    suspend fun insert(entry: ActivityLogEntry): Long

    @Query("SELECT * FROM activity_log ORDER BY timestampMillis DESC")
    suspend fun getAll(): List<ActivityLogEntry>

    @Query("SELECT * FROM activity_log ORDER BY timestampMillis DESC")
    fun observeAll(): LiveData<List<ActivityLogEntry>>

    @Query("DELETE FROM activity_log")
    suspend fun deleteAll()
}
