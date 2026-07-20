package com.odinga.spotune

import android.app.Activity
import android.graphics.Point
import android.graphics.Rect
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.PopupWindow

class KeyboardHeightProvider(
    private val activity: Activity,
    private val onHeightChanged: (Int) -> Unit
) : PopupWindow(activity) {

    private val popupView: View
    private val screenSize = Point()

    init {
        val inflater = activity.getSystemService(Activity.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        popupView = inflater.inflate(R.layout.popupwindow, null, false)
        contentView = popupView

        width = 0
        height = WindowManager.LayoutParams.MATCH_PARENT

        softInputMode =
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE

        inputMethodMode = INPUT_METHOD_NEEDED

        popupView.viewTreeObserver.addOnGlobalLayoutListener {
            handleOnGlobalLayout()
        }
    }

    fun start() {
        val parent = activity.findViewById<View>(android.R.id.content)

        if (!isShowing) {
            showAtLocation(
                parent,
                Gravity.NO_GRAVITY,
                0,
                0
            )
        }
    }

    fun stop() {
        dismiss()
    }

    private fun handleOnGlobalLayout() {
        @Suppress("DEPRECATION")
        activity.windowManager.defaultDisplay.getSize(screenSize)
        val rect = Rect()
        popupView.getWindowVisibleDisplayFrame(rect)

        val keyboardHeight = (screenSize.y - rect.bottom).coerceAtLeast(0)

        onHeightChanged(keyboardHeight)
    }
}
