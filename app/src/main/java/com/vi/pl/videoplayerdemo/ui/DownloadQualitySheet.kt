package com.vi.pl.videoplayerdemo.ui

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.vi.pl.videoplayerdemo.R
import java.util.Locale

class DownloadQualitySheet(
    context: Context,
    availableOptions: List<Option>,
    videoName: String,
    durationSec: Double,
    private val onDownload: (Int) -> Unit,
) : BottomSheetDialog(context, R.style.FilterBottomSheetTheme) {

    data class Option(val height: Int)

    private val options = availableOptions.sortedByDescending(Option::height)
        .distinctBy(Option::height).take(3)
    private var selected = options.firstOrNull() ?: Option(360)

    init {
        // 用 Dialog 自身（FilterBottomSheetTheme）的 inflater，避免宿主上下文主题不符导致 MaterialButton 崩溃
        val content = layoutInflater.inflate(R.layout.bottom_sheet_download_quality, null)
        setContentView(content)
        content.findViewById<TextView>(R.id.quality_video_name).text = videoName
        val container = content.findViewById<LinearLayout>(R.id.quality_options)
        val rows = mutableListOf<View>()
        this.options.forEach { option ->
            val row = layoutInflater.inflate(R.layout.item_download_quality, container, false)
            row.findViewById<TextView>(R.id.quality_label).text = context.getString(R.string.quality_label, option.height)
            row.findViewById<TextView>(R.id.quality_meta).text =
                context.getString(R.string.quality_meta, estimatedSize(option.height, durationSec))
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
            onDownload(selected.height)
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
        fun optionsFor(sourceHeight: Int): List<Option> {
            val cap = sourceHeight.takeIf { it > 0 } ?: 1080
            return listOf(1080, 720, 480, 360).filter { it <= cap }.take(3)
                .ifEmpty { listOf(cap.coerceAtMost(1080)) }.map(::Option)
        }

        fun displayHeight(sourceHeight: Int): Int = when {
            sourceHeight >= 1080 -> 1080
            sourceHeight >= 720 -> 720
            sourceHeight >= 480 -> 480
            sourceHeight >= 360 -> 360
            else -> sourceHeight.coerceAtLeast(1)
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
