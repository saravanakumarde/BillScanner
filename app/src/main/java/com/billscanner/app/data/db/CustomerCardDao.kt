package com.billscanner.app.data.db

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

/** A card plus a representative thumbnail (its first photo, by sortOrder), for the list screen. */
data class CustomerCardWithThumbnail(
    val id: Long,
    val storeName: String,
    val notes: String,
    val createdAtMillis: Long,
    val thumbnailUri: String?,
    val photoCount: Int
)

@Dao
interface CustomerCardDao {

    @Insert
    suspend fun insertCard(card: CustomerCard): Long

    @Update
    suspend fun updateCard(card: CustomerCard)

    @Delete
    suspend fun deleteCard(card: CustomerCard)

    @Query("SELECT * FROM customer_cards WHERE id = :id")
    suspend fun getCardById(id: Long): CustomerCard?

    @Insert
    suspend fun insertPhoto(photo: CustomerCardPhoto): Long

    @Query("SELECT * FROM customer_card_photos WHERE cardId = :cardId ORDER BY sortOrder ASC")
    suspend fun getPhotosForCard(cardId: Long): List<CustomerCardPhoto>

    @Query("SELECT * FROM customer_card_photos WHERE cardId = :cardId ORDER BY sortOrder ASC")
    fun observePhotosForCard(cardId: Long): LiveData<List<CustomerCardPhoto>>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM customer_card_photos WHERE cardId = :cardId")
    suspend fun getMaxSortOrder(cardId: Long): Int

    /** Appends a new photo after whatever the card's current last photo is. */
    @Transaction
    suspend fun appendPhoto(cardId: Long, imageUri: String): Long {
        val nextOrder = getMaxSortOrder(cardId) + 1
        return insertPhoto(CustomerCardPhoto(cardId = cardId, imageUri = imageUri, sortOrder = nextOrder))
    }

    @Delete
    suspend fun deletePhoto(photo: CustomerCardPhoto)

    @Query("SELECT * FROM customer_card_photos WHERE id = :id")
    suspend fun getPhotoById(id: Long): CustomerCardPhoto?

    @Query(
        """
        SELECT c.id, c.storeName, c.notes, c.createdAtMillis,
               (SELECT p.imageUri FROM customer_card_photos p
                WHERE p.cardId = c.id ORDER BY p.sortOrder ASC LIMIT 1) AS thumbnailUri,
               (SELECT COUNT(*) FROM customer_card_photos p WHERE p.cardId = c.id) AS photoCount
        FROM customer_cards c
        ORDER BY c.storeName COLLATE NOCASE ASC
        """
    )
    fun observeAllCards(): LiveData<List<CustomerCardWithThumbnail>>

    @Transaction
    suspend fun deleteCardWithPhotos(card: CustomerCard): List<CustomerCardPhoto> {
        val photos = getPhotosForCard(card.id)
        deleteCard(card) // CASCADE removes the customer_card_photos rows too
        return photos
    }
}
