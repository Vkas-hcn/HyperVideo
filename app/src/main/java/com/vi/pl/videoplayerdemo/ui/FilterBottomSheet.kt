package com.vi.pl.videoplayerdemo.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.vi.pl.videoplayerdemo.R
import com.vi.pl.videoplayerdemo.local.VideoSort

class FilterBottomSheet(
    context: Context,
    private val current: VideoSort,
    private val onApply: (VideoSort) -> Unit,
) : BottomSheetDialog(context, R.style.FilterBottomSheetTheme) {
    private var selected = current

    init {
        val content = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_filter, null)
        setContentView(content)
        val optionsContainer = content.findViewById<LinearLayout>(R.id.filter_options)
        val optionViews = mutableListOf<View>()

        OPTIONS.forEach { (sort, icon, label) ->
            val row = LayoutInflater.from(context).inflate(R.layout.item_filter_option, optionsContainer, false)
            row.findViewById<ImageView>(R.id.option_icon).setImageResource(icon)
            row.findViewById<TextView>(R.id.option_label).setText(label)
            row.setOnClickListener {
                selected = sort
                bindRadios(optionViews)
            }
            row.tag = sort
            optionsContainer.addView(row)
            optionViews += row
        }
        bindRadios(optionViews)

        content.findViewById<View>(R.id.btn_apply_filter).setOnClickListener {
            onApply(selected)
            dismiss()
        }
        content.findViewById<View>(R.id.btn_cancel_filter).setOnClickListener { dismiss() }
    }

    private fun bindRadios(rows: List<View>) {
        rows.forEach { row ->
            val checked = row.tag == selected
            row.findViewById<ImageView>(R.id.option_radio).setImageResource(
                if (checked) R.drawable.bg_radio_checked else R.drawable.bg_radio_unchecked,
            )
        }
    }

    private companion object {
        val OPTIONS = listOf(
            Triple(VideoSort.DATE_NEWEST, R.drawable.ic_date_newest, R.string.sort_date_newest),
            Triple(VideoSort.DATE_OLDEST, R.drawable.ic_date_oldest, R.string.sort_date_oldest),
            Triple(VideoSort.NAME_AZ, R.drawable.ic_sort_a_z_up, R.string.sort_name_az),
            Triple(VideoSort.NAME_ZA, R.drawable.ic_sort_z_a_down, R.string.sort_name_za),
            Triple(VideoSort.SIZE_LARGEST, R.drawable.ic_sort_size_largest_up, R.string.sort_size_largest),
            Triple(VideoSort.SIZE_SMALLEST, R.drawable.ic_sort_size_smallest_down, R.string.sort_size_smallest),
        )
    }
}
