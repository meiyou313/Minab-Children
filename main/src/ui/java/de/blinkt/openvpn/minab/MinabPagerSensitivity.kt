/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * Minab Beta v0.8 — tab swiping was too eager: a tiny drag flipped
 * the page, so tabs changed by accident. ViewPager2 scrolls through
 * its internal RecyclerView, whose touch slop decides how far a
 * finger must move before the page starts moving. Raising that slop
 * makes a swipe need a deliberate drag. Taps on the tabs are
 * untouched (they do not go through the RecyclerView). Reflection
 * is used because RecyclerView exposes no setter for the slop; any
 * failure silently leaves the default behaviour, and the slop is
 * only ever raised, never lowered.
 */

package de.blinkt.openvpn.minab

import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

object MinabPagerSensitivity {
    fun reduceSensitivity(pager: ViewPager2, factor: Int = 3) {
        try {
            val recycler = pager.getChildAt(0) as? RecyclerView ?: return
            val field = RecyclerView::class.java.getDeclaredField("mTouchSlop")
            field.isAccessible = true
            val slop = field.getInt(recycler)
            if (slop > 0) field.setInt(recycler, slop * factor)
        } catch (ignored: Exception) {
            // Reflection failed (field renamed, etc.): swiping just
            // keeps the stock sensitivity. Never crash the activity.
        }
    }
}
