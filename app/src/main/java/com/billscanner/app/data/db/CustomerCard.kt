package com.billscanner.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A customer/loyalty card (e.g. a supermarket's advantage card) the user
 * wants kept in the app instead of carried physically. A card can have
 * multiple photos (see [CustomerCardPhoto]) — typically front and back,
 * or several cards from the same chain — so the image itself is not
 * stored here.
 */
@Entity(tableName = "customer_cards")
data class CustomerCard(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val storeName: String,
    val notes: String = "",
    val createdAtMillis: Long = System.currentTimeMillis()
)
