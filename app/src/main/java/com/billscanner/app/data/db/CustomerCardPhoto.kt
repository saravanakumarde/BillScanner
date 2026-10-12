package com.billscanner.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One photo of a [CustomerCard] (e.g. its front, or its back). A card can
 * have several, added over time — added in the order the user takes/picks
 * them, shown in that same order in the swipeable card detail viewer.
 *
 * Stored in the app's own private storage (file://), the same as bill
 * photos — see [com.billscanner.app.util.PrivatePhotoStorage].
 */
@Entity(
    tableName = "customer_card_photos",
    foreignKeys = [
        ForeignKey(
            entity = CustomerCard::class,
            parentColumns = ["id"],
            childColumns = ["cardId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("cardId")]
)
data class CustomerCardPhoto(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    val imageUri: String,
    /** Display order within the card, lowest first. New photos are appended (added at the end). */
    val sortOrder: Int
)
