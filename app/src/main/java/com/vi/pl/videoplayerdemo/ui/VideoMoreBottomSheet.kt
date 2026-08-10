package com.vi.pl.videoplayerdemo.ui

import android.content.Context
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.vi.pl.videoplayerdemo.R

class VideoMoreBottomSheet(
    context: Context,
    private val onAction: (Action) -> Unit,
) : BottomSheetDialog(context, R.style.FilterBottomSheetTheme) {

    enum class Action {
        PLAY_BACKGROUND,
        PLAY_MUTED,
        FAVORITE,
        RENAME,
        SHARE,
        DELETE,
    }

    init {
        val content = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_video_more, null)
        setContentView(content)
        val container = content.findViewById<LinearLayout>(R.id.more_options)
        OPTIONS.forEach { option ->
            val row = LayoutInflater.from(context).inflate(R.layout.item_video_more_option, container, false)
            val icon = row.findViewById<ImageView>(R.id.option_icon)
            val label = row.findViewById<TextView>(R.id.option_label)
            icon.setImageResource(option.icon)
            label.setText(option.label)
            if (option.destructive) {
                val red = ContextCompat.getColor(context, R.color.brand_red)
                icon.setColorFilter(red)
            }
            row.setOnClickListener {
                dismiss()
                onAction(option.action)
            }
            container.addView(row)
        }
    }

    private data class Option(
        val action: Action,
        @param:DrawableRes val icon: Int,
        @param:StringRes val label: Int,
        val destructive: Boolean = false,
    )

    private companion object {
        val OPTIONS = listOf(
            Option(Action.PLAY_BACKGROUND, R.drawable.ic_playinbackground, R.string.play_in_background),
            Option(Action.PLAY_MUTED, R.drawable.ic_playmuted, R.string.play_muted),
            Option(Action.FAVORITE, R.drawable.ic_favorite_nor, R.string.favorite),
            Option(Action.RENAME, R.drawable.ic_rename, R.string.rename),
            Option(Action.SHARE, R.drawable.ic_share, R.string.share),
            Option(Action.DELETE, R.drawable.ic_deltele, R.string.delete, destructive = true),
        )
    }
}
