package dev.nekuz.sceneremote

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.TextView

data class SceneCell(val id: String, val label: String, val current: Boolean, val pending: Boolean, val enabled: Boolean)

sealed class Row {
    data class Header(val text: String) : Row()

    data class Note(val text: String) : Row()

    data class Scenes(val cells: List<SceneCell>, val columns: Int) : Row()

    data class Audio(val id: String, val label: String, val muted: Boolean, val enabled: Boolean) : Row()
}

class RowAdapter(
    private val context: Context,
    private val onScene: (String) -> Unit,
    private val onMute: (String, Boolean) -> Unit,
) : BaseAdapter() {
    var rows: List<Row> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    private val inflater = LayoutInflater.from(context)

    override fun getCount() = rows.size
    override fun getItem(position: Int) = rows[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun getViewTypeCount() = 4
    override fun isEnabled(position: Int) = false

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is Row.Header -> 0
        is Row.Note -> 1
        is Row.Scenes -> 2
        is Row.Audio -> 3
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
        when (val row = rows[position]) {
            is Row.Header -> text(convertView, parent, R.layout.row_header, row.text)
            is Row.Note -> text(convertView, parent, R.layout.row_note, row.text)
            is Row.Scenes -> scenes(convertView, parent, row)
            is Row.Audio -> audio(convertView, parent, row)
        }

    private fun text(convertView: View?, parent: ViewGroup, layout: Int, text: String): View {
        val view = convertView ?: inflater.inflate(layout, parent, false)
        (view as TextView).text = text
        return view
    }

    private fun scenes(convertView: View?, parent: ViewGroup, row: Row.Scenes): View {
        val view = convertView ?: inflater.inflate(R.layout.row_scenes, parent, false)
        val buttons = listOf<Button>(
            view.findViewById(R.id.scene1),
            view.findViewById(R.id.scene2),
            view.findViewById(R.id.scene3),
        )
        buttons.forEachIndexed { index, button ->
            val cell = row.cells.getOrNull(index)
            button.visibility = when {
                cell != null -> View.VISIBLE
                index < row.columns -> View.INVISIBLE
                else -> View.GONE
            }
            if (cell != null) bindScene(button, cell) else button.setOnClickListener(null)
        }
        return view
    }

    private fun bindScene(button: Button, cell: SceneCell) {
        button.text = when {
            cell.pending -> context.getString(R.string.scene_switching, cell.label)
            cell.current -> context.getString(R.string.scene_on_program, cell.label)
            else -> cell.label
        }
        button.setBackgroundResource(if (cell.current) R.drawable.bg_button_primary else R.drawable.bg_button_outline)
        button.setTextColor(context.getColor(if (cell.current) R.color.on_brand else R.color.text_primary))
        button.isEnabled = cell.enabled
        button.alpha = if (cell.enabled) 1f else DISABLED_ALPHA
        button.setOnClickListener { onScene(cell.id) }
    }

    private fun audio(convertView: View?, parent: ViewGroup, row: Row.Audio): View {
        val view = convertView ?: inflater.inflate(R.layout.row_audio, parent, false)
        view.findViewById<TextView>(R.id.audioName).text = row.label
        val toggle = view.findViewById<Button>(R.id.audioToggle)
        toggle.setText(if (row.muted) R.string.audio_muted else R.string.audio_live)
        toggle.contentDescription = context.getString(
            if (row.muted) R.string.audio_unmute_description else R.string.audio_mute_description,
            row.label,
        )
        toggle.setBackgroundResource(if (row.muted) R.drawable.bg_button_live else R.drawable.bg_button_outline)
        toggle.setTextColor(context.getColor(if (row.muted) R.color.on_brand else R.color.text_primary))
        toggle.isEnabled = row.enabled
        toggle.alpha = if (row.enabled) 1f else DISABLED_ALPHA
        toggle.setOnClickListener { onMute(row.id, !row.muted) }
        return view
    }

    private companion object {
        const val DISABLED_ALPHA = 0.45f
    }
}
