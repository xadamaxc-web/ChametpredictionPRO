package com.chamet.guesser

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A simple 3-row ranking list where each row has UP / DOWN buttons.
 * (A full drag-and-drop implementation needs ItemTouchHelper, which
 * is heavy for an overlay. Up/Down buttons are more reliable here.)
 *
 * Must have (Context, AttributeSet) constructor so it can be inflated
 * from overlay_result.xml.
 */
class DragReorderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val rows = mutableListOf<TextView>()
    var ranking: MutableList<String> = mutableListOf()
        private set

    private var onChanged: ((List<String>) -> Unit)? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#0F3460"))
        setPadding(20, 20, 20, 20)
    }

    fun setRanking(cars: List<String>, onChange: (List<String>) -> Unit) {
        ranking = cars.toMutableList()
        onChanged = onChange
        rebuild()
    }

    private fun rebuild() {
        removeAllViews()
        rows.clear()

        ranking.forEachIndexed { index, car ->
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(10, 12, 10, 12)
                setBackgroundColor(Color.parseColor("#1A1A2E"))
            }

            val rankLabel = TextView(context).apply {
                text = "${index + 1}"
                setTextColor(Color.parseColor("#FCA311"))
                textSize = 20f
                width = 60
            }

            val carLabel = TextView(context).apply {
                text = car
                setTextColor(Color.WHITE)
                textSize = 18f
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            }

            val up = TextView(context).apply {
                text = "▲"
                setTextColor(Color.parseColor("#2ECC71"))
                textSize = 22f
                setPadding(20, 0, 20, 0)
                setOnClickListener { moveUp(index) }
            }

            val down = TextView(context).apply {
                text = "▼"
                setTextColor(Color.parseColor("#E74C3C"))
                textSize = 22f
                setPadding(20, 0, 20, 0)
                setOnClickListener { moveDown(index) }
            }

            row.addView(rankLabel)
            row.addView(carLabel)
            row.addView(up)
            row.addView(down)

            addView(row)
            rows.add(carLabel)

            // Divider
            addView(View(context).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 2)
                setBackgroundColor(Color.parseColor("#2A2A4E"))
            })
        }
    }

    private fun moveUp(index: Int) {
        if (index <= 0) return
        val tmp = ranking[index - 1]
        ranking[index - 1] = ranking[index]
        ranking[index] = tmp
        rebuild()
        onChanged?.invoke(ranking)
    }

    private fun moveDown(index: Int) {
        if (index >= ranking.size - 1) return
        val tmp = ranking[index + 1]
        ranking[index + 1] = ranking[index]
        ranking[index] = tmp
        rebuild()
        onChanged?.invoke(ranking)
    }
}
