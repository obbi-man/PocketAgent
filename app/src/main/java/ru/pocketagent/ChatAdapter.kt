package ru.pocketagent

import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors

data class CardAction(val label: String, val run: (android.app.Activity) -> Unit)

data class ChatItem(
    val kind: Kind, var text: String,
    var card: Card? = null, var debug: String? = null,
    var actions: List<CardAction> = emptyList(),
    var chip: String? = null
) {
    enum class Kind { USER, ASSISTANT, CARD, SYSTEM }
}

class ChatAdapter(
    val items: MutableList<ChatItem>,
    private val scale: () -> Float,
    private val showDebug: () -> Boolean,
    private val onTapAssistant: (String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    var onAction: (CardAction) -> Unit = {}

    class MsgVH(v: View) : RecyclerView.ViewHolder(v) {
        val row: LinearLayout = v.findViewById(R.id.row)
        val text: TextView = v.findViewById(R.id.text)
        val chip: TextView = v.findViewById(R.id.chip)
    }
    class CardVH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: TextView = v.findViewById(R.id.cardIcon)
        val iconImg: android.widget.ImageView = v.findViewById(R.id.cardIconImg)
        val title: TextView = v.findViewById(R.id.cardTitle)
        val body: TextView = v.findViewById(R.id.cardBody)
        val debug: TextView = v.findViewById(R.id.cardDebug)
        val actions: ViewGroup = v.findViewById(R.id.cardActions)
    }

    override fun getItemViewType(position: Int) = if (items[position].kind == ChatItem.Kind.CARD) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == 1) CardVH(inf.inflate(R.layout.item_card, parent, false))
        else MsgVH(inf.inflate(R.layout.item_message, parent, false))
    }

    override fun getItemCount() = items.size

    private fun color(v: View, attr: Int) = MaterialColors.getColor(v, attr)

    private fun iconFor(e: String): Int = when {
        e.startsWith("🔔") -> R.drawable.ic_bell
        e.startsWith("⏰") || e.startsWith("🕐") -> R.drawable.ic_alarm
        e.startsWith("⏱") -> R.drawable.ic_timer
        e.startsWith("📝") -> R.drawable.ic_edit
        e.startsWith("🗑") -> R.drawable.ic_trash
        e.startsWith("🌐") -> R.drawable.ic_globe
        e.startsWith("🧮") -> R.drawable.ic_calc
        else -> 0
    }

    override fun onBindViewHolder(vh: RecyclerView.ViewHolder, pos: Int) {
        val it = items[pos]
        val k = scale()
        if (vh is CardVH) {
            val c = it.card ?: Card("ℹ️", it.text)
            val res = iconFor(c.icon)
            if (res != 0) { vh.iconImg.setImageResource(res); vh.iconImg.visibility = View.VISIBLE; vh.icon.visibility = View.GONE }
            else { vh.icon.text = c.icon; vh.icon.visibility = View.VISIBLE; vh.iconImg.visibility = View.GONE }
            vh.title.text = c.title; vh.title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f * k)
            vh.body.text = c.body; vh.body.visibility = if (c.body.isBlank()) View.GONE else View.VISIBLE
            vh.body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f * k)
            val dbg = it.debug
            vh.debug.visibility = if (showDebug() && dbg != null) View.VISIBLE else View.GONE
            vh.debug.text = dbg ?: ""
            vh.actions.removeAllViews()
            vh.actions.visibility = if (it.actions.isEmpty()) View.GONE else View.VISIBLE
            for (a in it.actions) {
                vh.actions.addView(MaterialButton(vh.itemView.context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = a.label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f * k); maxLines = 1
                    minHeight = (52 * resources.displayMetrics.density).toInt()
                    setOnClickListener { onAction(a) }
                }, ViewGroup.LayoutParams(-2, -2))
            }
            return
        }
        val h = vh as MsgVH
        h.text.text = it.text
        h.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, (if (it.kind == ChatItem.Kind.SYSTEM) 16f else 19f) * k)
        h.chip.visibility = if (it.chip != null) View.VISIBLE else View.GONE
        h.chip.text = it.chip ?: ""
        h.chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * k)
        h.text.setOnClickListener(null); h.text.isClickable = false
        h.text.setOnLongClickListener { v ->
            val cm = v.context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("PocketAgent", it.text))
            android.widget.Toast.makeText(v.context, "Скопировано", android.widget.Toast.LENGTH_SHORT).show(); true
        }
        val ctx = h.text.context
        h.text.backgroundTintList = null
        h.text.setTextColor(ctx.getColor(R.color.pa_text))
        h.text.gravity = Gravity.START
        when (it.kind) {
            ChatItem.Kind.USER -> {
                h.row.gravity = Gravity.END
                h.text.setBackgroundResource(R.drawable.bubble_user)
            }
            ChatItem.Kind.ASSISTANT -> {
                h.row.gravity = Gravity.START
                h.text.setBackgroundResource(R.drawable.bubble_assistant)
                h.text.setOnClickListener { _ -> onTapAssistant(it.text) }   // нажатие — прочитать вслух
            }
            else -> {
                h.row.gravity = Gravity.CENTER_HORIZONTAL
                h.text.background = null
                h.text.gravity = Gravity.CENTER
                h.text.setTextColor(ctx.getColor(R.color.pa_sub))
            }
        }
    }
}
