package com.vi.pl.videoplayerdemo.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.vi.pl.videoplayerdemo.R
import java.util.Locale

class DownloadQualitySheet(
    context: Context,
    sourceHeight: Int,
    durationSec: Double,
    private val onDownload: (Int) -> Unit,
    private val onSubscribe: () -> Unit,
) : BottomSheetDialog(context, R.style.FilterBottomSheetTheme) {

    data class Option(val height: Int, val locked: Boolean)

    private val options = optionsFor(sourceHeight)
    private var selected = options.firstOrNull { !it.locked } ?: options.first()

    init {
        val content = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_download_quality, null)
        setContentView(content)
        val container = content.findViewById<LinearLayout>(R.id.quality_options)
        val rows = mutableListOf<View>()
        options.forEach { option ->
            val row = LayoutInflater.from(context).inflate(R.layout.item_download_quality, container, false)
            row.findViewById<TextView>(R.id.quality_label).text = context.getString(R.string.quality_label, option.height)
            row.findViewById<TextView>(R.id.quality_meta).text =
                context.getString(R.string.quality_meta, estimatedSize(option.height, durationSec))
            row.findViewById<View>(R.id.quality_lock).visibility = if (option.locked) View.VISIBLE else View.GONE
            row.setOnClickListener {
                selected = option
                bindRadios(rows)
            }
            row.tag = option
            container.addView(row)
            rows += row
        }
        bindRadios(rows)
        content.findViewById<View>(R.id.btn_quality_download).setOnClickListener {
            if (selected.locked) {
                // ponytail: subscription unlock is a later billing feature
                onSubscribe()
            } else {
                onDownload(selected.height)
            }
            dismiss()
        }
        content.findViewById<View>(R.id.btn_quality_subscribe).setOnClickListener {
            onSubscribe()
            dismiss()
        }
    }

    private fun bindRadios(rows: List<View>) {
        rows.forEach { row ->
            val checked = row.tag == selected
            row.findViewById<ImageView>(R.id.quality_radio).setImageResource(
                if (checked) R.drawable.bg_radio_checked else R.drawable.bg_radio_unchecked,
            )
        }
    }

    companion object {
        private val LADDER = listOf(1080, 720, 480, 360)

        fun optionsFor(sourceHeight: Int): List<Option> {
            val cap = sourceHeight.takeIf { it > 0 } ?: 1080
            val heights = LADDER.filter { it <= cap }.take(3).ifEmpty { listOf(cap.coerceAtMost(1080)) }
            return heights.mapIndexed { index, height -> Option(height, locked = index == 0 && heights.size > 1) }
        }

        fun estimatedSize(height: Int, durationSec: Double): String {
            val seconds = durationSec.takeIf { it.isFinite() && it in 1.0..36_000.0 } ?: 30.0
            val mbps = when {
                height >= 1080 -> 2.5
                height >= 720 -> 1.5
                else -> 0.8
            }
            return String.format(Locale.US, "%.1f MB", mbps * seconds / 8.0)
        }
    }
}
