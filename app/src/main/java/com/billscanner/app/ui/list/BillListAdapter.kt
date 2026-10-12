package com.billscanner.app.ui.list

import android.graphics.Color
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.billscanner.app.R
import com.billscanner.app.data.db.BillWithCategory
import com.billscanner.app.util.CurrencyFormat
import com.billscanner.app.util.DateFormat
import com.google.android.material.card.MaterialCardView

class BillListAdapter(
    private val onClick: (BillWithCategory) -> Unit
) : RecyclerView.Adapter<BillListAdapter.VH>() {

    private val items = mutableListOf<BillWithCategory>()

    fun submitList(newItems: List<BillWithCategory>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                items[oldPos].id == newItems[newPos].id
            override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                items[oldPos] == newItems[newPos]
        })
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_bill, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], onClick)
    }

    override fun getItemCount() = items.size

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivThumb: ImageView = itemView.findViewById(R.id.ivThumb)
        private val tvStore: TextView = itemView.findViewById(R.id.tvStoreName)
        private val tvDate: TextView = itemView.findViewById(R.id.tvDate)
        private val tvCategory: TextView = itemView.findViewById(R.id.tvCategoryChip)
        private val tvAmount: TextView = itemView.findViewById(R.id.tvAmount)
        private val card: MaterialCardView = itemView as MaterialCardView

        fun bind(item: BillWithCategory, onClick: (BillWithCategory) -> Unit) {
            tvStore.text = item.storeNameEnglish.ifEmpty { item.storeNameOriginal }
            tvDate.text = DateFormat.display(item.billDateMillis)
            tvAmount.text = CurrencyFormat.format(item.totalAmount, item.currency)
            tvCategory.text = item.categoryName ?: "Other"

            val bg = (tvCategory.background ?: androidx.core.content.ContextCompat.getDrawable(
                itemView.context, com.billscanner.app.R.drawable.bg_category_chip
            ))?.mutate()
            if (bg != null) {
                try {
                    DrawableCompat.setTint(bg, Color.parseColor(item.colorHex.ifEmpty { "#607D8B" }))
                } catch (e: Exception) {
                    DrawableCompat.setTint(bg, Color.parseColor("#607D8B"))
                }
                tvCategory.background = bg
            }

            if (item.imageUri.isNotEmpty()) {
                try {
                    ivThumb.setImageURI(Uri.parse(item.imageUri))
                } catch (e: Exception) {
                    ivThumb.setImageDrawable(null)
                }
            } else {
                ivThumb.setImageDrawable(null)
            }

            card.setOnClickListener { onClick(item) }
        }
    }
}
