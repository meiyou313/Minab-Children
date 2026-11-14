/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabCategoryReorder (v0.9.1, fix #8): the "Reorder groups"
 * dialog, shared by the Home and Configs tabs. Each row has an
 * up / down arrow; the order is saved to CategoryStore on every
 * move, and onChanged runs so the caller can rebuild its chips
 * and list. The favorites tab is pinned first and is not in this
 * list, so it can never be moved.
 */

package de.blinkt.openvpn.minab

import android.app.AlertDialog
import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import de.blinkt.openvpn.R
import java.util.Collections

object MinabCategoryReorder {

    /** Show the reorder dialog. [onChanged] runs after every move. */
    fun show(context: Context, onChanged: () -> Unit) {
        val groups = CategoryStore.getOrderedCategories(context).toMutableList()
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val hint = TextView(context).apply {
            setText(R.string.minab_reorder_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(context.resources.getColor(R.color.minab_text_secondary))
        }
        root.addView(hint, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))

        val listView = ListView(context)
        root.addView(listView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))

        val adapter = object : ArrayAdapter<String>(context, 0, groups) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, dp(4))
                }
                row.removeAllViews()
                val name = TextView(context).apply {
                    text = getItem(position)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(context.resources.getColor(R.color.minab_text_primary))
                }
                row.addView(name, LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val up = Button(context).apply {
                    text = "↑"
                    minHeight = 0
                    minimumHeight = 0
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                    isEnabled = position > 0
                    setOnClickListener {
                        Collections.swap(groups, position, position - 1)
                        CategoryStore.saveOrder(context, groups)
                        notifyDataSetChanged()
                        onChanged()
                    }
                }
                row.addView(up)
                val down = Button(context).apply {
                    text = "↓"
                    minHeight = 0
                    minimumHeight = 0
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                    isEnabled = position < count - 1
                    setOnClickListener {
                        Collections.swap(groups, position, position + 1)
                        CategoryStore.saveOrder(context, groups)
                        notifyDataSetChanged()
                        onChanged()
                    }
                }
                row.addView(down)
                return row
            }
        }
        listView.adapter = adapter

        AlertDialog.Builder(context)
            .setTitle(R.string.minab_reorder_groups)
            .setView(root)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
