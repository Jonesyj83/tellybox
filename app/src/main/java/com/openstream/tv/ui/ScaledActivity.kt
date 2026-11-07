package com.openstream.tv.ui

import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Applies the activity's scale once to each attached view, including recycled list rows. */
open class ScaledActivity : AppCompatActivity() {
    val uiScale by lazy { resources.configuration.let { UiScale(it.screenWidthDp, it.screenHeightDp) } }
    private val sized = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.viewTreeObserver.addOnPreDrawListener {
            val content = findViewById<ViewGroup>(android.R.id.content)
            var changed = false
            fun visit(view: View) {
                if (sized.add(view)) {
                    changed = true
                    fun px(value: Int) = if (value > 0) (value * uiScale.scale).roundToInt() else value
                    view.layoutParams?.let { p ->
                        p.width = px(p.width); p.height = px(p.height)
                        if (p is ViewGroup.MarginLayoutParams) {
                            p.leftMargin = px(p.leftMargin); p.rightMargin = px(p.rightMargin)
                            p.topMargin = px(p.topMargin); p.bottomMargin = px(p.bottomMargin)
                            p.marginStart = px(p.marginStart); p.marginEnd = px(p.marginEnd)
                        }
                        view.layoutParams = p
                    }
                    if (view is android.widget.Button) {
                        view.minimumHeight = (30 * resources.displayMetrics.density).roundToInt()
                        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    }
                    view.minimumWidth = px(view.minimumWidth)
                    view.minimumHeight = px(view.minimumHeight)
                    val scrolls = view is RecyclerView || view is ScrollView || view is HorizontalScrollView
                    val inset = if (scrolls) (uiScale.dp(8) * resources.displayMetrics.density).roundToInt() else 0
                    view.setPadding(px(view.paddingLeft), px(view.paddingTop), px(view.paddingRight) + inset, px(view.paddingBottom) + inset)
                    if (scrolls) (view as ViewGroup).clipToPadding = false
                    if (view is TextView) {
                        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, view.textSize * uiScale.scale)
                        if (view.maxWidth < Int.MAX_VALUE) view.maxWidth = px(view.maxWidth)
                    }
                }
                if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
            }
            for (i in 0 until content.childCount) visit(content.getChildAt(i))
            !changed
        }
    }
}
