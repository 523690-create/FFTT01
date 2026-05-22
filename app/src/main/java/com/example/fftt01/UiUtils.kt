package com.example.fftt01

import android.graphics.Paint
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import android.widget.Spinner

object UiUtils {
    /**
     * Dynamically scales the text size of a view (TextView, Button, CheckBox, etc.) to fit its bounds.
     */
    fun autoScaleText(view: View?, horizontalMargin: Int = 8, verticalMargin: Int = 8) {
        if (view == null || view !is TextView) return
        
        view.post {
            if (view.width <= 0 || view.height <= 0) {
                // If bounds not ready, try again shortly
                view.postDelayed({ autoScaleText(view, horizontalMargin, verticalMargin) }, 50)
                return@post
            }
            
            val width = view.width - view.paddingLeft - view.paddingRight - horizontalMargin
            val height = view.height - view.paddingTop - view.paddingBottom - verticalMargin
            if (width <= 0 || height <= 0) return@post
            
            val text = view.text.toString()
            if (text.isEmpty()) return@post
            
            val paint = Paint()
            paint.set(view.paint)
            
            var maxTextSize = 250f 
            var minTextSize = 2f
            var resultTextSize = minTextSize
            
            // Binary search for the best fit
            while (maxTextSize - minTextSize > 0.5f) {
                val mid = (maxTextSize + minTextSize) / 2f
                paint.textSize = mid
                
                val lines = text.split("\n")
                var totalHeight = 0f
                var maxWidth = 0f
                
                val fm = paint.fontMetrics
                val lineH = fm.bottom - fm.top
                
                for (line in lines) {
                    val w = paint.measureText(line)
                    if (w > maxWidth) maxWidth = w
                    totalHeight += lineH
                }
                
                if (maxWidth <= width && totalHeight <= height) {
                    resultTextSize = mid
                    minTextSize = mid
                } else {
                    maxTextSize = mid
                }
            }
            
            // Ensure we don't go too small or too large
            val finalSize = resultTextSize.coerceAtMost(height.toFloat() * 0.95f)
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, finalSize)
        }
    }

    /**
     * Scales the selected view of a Spinner.
     */
    fun autoScaleSpinner(spinner: Spinner?) {
        if (spinner == null) return
        spinner.post {
            val selectedView = spinner.selectedView
            if (selectedView != null) {
                autoScaleText(selectedView)
            }
        }
    }
}
