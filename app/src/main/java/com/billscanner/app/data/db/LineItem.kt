package com.billscanner.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single product/line on a bill, e.g. "Milch 1L ... 1.19".
 * Kept separate from Bill so per-item category overrides and item-level
 * stats (e.g. "how much have I spent on milk this year") are possible later.
 */
@Entity(
    tableName = "line_items",
    foreignKeys = [
        ForeignKey(
            entity = Bill::class,
            parentColumns = ["id"],
            childColumns = ["billId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("billId"), Index("categoryId")]
)
data class LineItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val billId: Long,

    val nameOriginal: String,
    val nameEnglish: String,

    val quantity: Double = 1.0,
    val unitPrice: Double? = null,
    val totalPrice: Double,

    // Category can differ from the bill's overall category (e.g. a pharmacy
    // run that also had a chocolate bar rung up as "Dining/Snacks").
    val categoryId: Long?
)
