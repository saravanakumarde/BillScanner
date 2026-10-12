package com.billscanner.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A spending category. Seeded with defaults on first run; user can add more
 * via ManageCategoriesActivity. [name] is always stored/displayed in English,
 * which is what the whole UI is built around per the app's requirements.
 */
@Entity(tableName = "categories")
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorHex: String = "#607D8B",
    val isDefault: Boolean = false
)

object DefaultCategories {
    // name to color, used only on first-run DB seed.
    val SEED = listOf(
        "Groceries" to "#4CAF50",
        "Household" to "#795548",
        "Pharmacy" to "#E91E63",
        "Electronics" to "#2196F3",
        "Clothing" to "#9C27B0",
        "Dining" to "#FF9800",
        "Transport" to "#009688",
        "Other" to "#607D8B"
    )
}
