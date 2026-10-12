package com.billscanner.app.ui.cards

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import androidx.recyclerview.widget.RecyclerView
import com.billscanner.app.R
import com.billscanner.app.data.db.CustomerCardPhoto
import com.billscanner.app.ui.widget.ZoomableImageView

class CardPhotoPagerAdapter(
    private val onRotate: (CustomerCardPhoto, degrees: Float, onDone: (Boolean) -> Unit) -> Unit,
    private val onDelete: (CustomerCardPhoto) -> Unit
) : RecyclerView.Adapter<CardPhotoPagerAdapter.PageViewHolder>() {

    private var photos: List<CustomerCardPhoto> = emptyList()

    fun submitList(newPhotos: List<CustomerCardPhoto>) {
        photos = newPhotos
        notifyDataSetChanged()
    }

    inner class PageViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        val ivPage: ZoomableImageView = itemView.findViewById(R.id.ivCardPage)
        val btnRotateLeft: ImageButton = itemView.findViewById(R.id.btnPageRotateLeft)
        val btnRotateRight: ImageButton = itemView.findViewById(R.id.btnPageRotateRight)
        val btnDelete: ImageButton = itemView.findViewById(R.id.btnPageDeletePhoto)
        val progress: ProgressBar = itemView.findViewById(R.id.pageRotateProgress)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_card_photo_page, parent, false)
        return PageViewHolder(view)
    }

    override fun getItemCount(): Int = photos.size

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val photo = photos[position]
        loadImage(holder)

        val rotate = { degrees: Float ->
            holder.progress.visibility = android.view.View.VISIBLE
            onRotate(photo, degrees) { success ->
                holder.progress.visibility = android.view.View.GONE
                if (success) loadImage(holder)
            }
        }
        holder.btnRotateLeft.setOnClickListener { rotate(-90f) }
        holder.btnRotateRight.setOnClickListener { rotate(90f) }
        holder.btnDelete.setOnClickListener { onDelete(photo) }
    }

    private fun loadImage(holder: PageViewHolder) {
        val photo = photos.getOrNull(holder.bindingAdapterPosition) ?: return
        try {
            // Same Uri can be reloaded after a rotation — ImageView can
            // treat an unchanged Uri as a no-op, so clear first.
            holder.ivPage.setImageURI(null)
            holder.ivPage.setImageURI(Uri.parse(photo.imageUri))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun getItemId(position: Int): Long = photos[position].id
}
