package com.billscanner.app.ui.cards

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.billscanner.app.R
import com.billscanner.app.data.db.CustomerCardWithThumbnail

class CustomerCardListAdapter(
    private val onClick: (CustomerCardWithThumbnail) -> Unit
) : ListAdapter<CustomerCardWithThumbnail, CustomerCardListAdapter.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<CustomerCardWithThumbnail>() {
            override fun areItemsTheSame(old: CustomerCardWithThumbnail, new: CustomerCardWithThumbnail) = old.id == new.id
            override fun areContentsTheSame(old: CustomerCardWithThumbnail, new: CustomerCardWithThumbnail) = old == new
        }
    }

    inner class ViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        val ivThumb: ImageView = itemView.findViewById(R.id.ivCardThumb)
        val tvStoreName: TextView = itemView.findViewById(R.id.tvCardStoreName)
        val tvPhotoCount: TextView = itemView.findViewById(R.id.tvCardPhotoCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_customer_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val card = getItem(position)
        holder.tvStoreName.text = card.storeName
        holder.tvPhotoCount.text = when (card.photoCount) {
            0 -> holder.itemView.context.getString(R.string.card_photo_count_none)
            1 -> holder.itemView.context.getString(R.string.card_photo_count_single)
            else -> holder.itemView.context.getString(R.string.card_photo_count_multi_format, card.photoCount)
        }

        if (!card.thumbnailUri.isNullOrEmpty()) {
            try {
                holder.ivThumb.setImageURI(Uri.parse(card.thumbnailUri))
            } catch (e: Exception) {
                holder.ivThumb.setImageDrawable(null)
            }
        } else {
            holder.ivThumb.setImageDrawable(null)
        }

        holder.itemView.setOnClickListener { onClick(card) }
    }
}
