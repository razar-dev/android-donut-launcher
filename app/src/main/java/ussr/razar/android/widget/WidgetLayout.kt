package ussr.razar.android.widget

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.graphics.drawable.AnimationDrawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import ussr.razar.android.content.LauncherIntent
import ussr.razar.android.content.LauncherIntent.Extra

abstract class WidgetLayout : ViewGroup {
    constructor(context: Context?) : super(context)
    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context?, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle)

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {}

    fun onViewportIn() {
        var child: View?
        var widgetView: AppWidgetHostView
        var widgetInfo: AppWidgetProviderInfo
        var intent: Intent
        for (i in childCount - 1 downTo 0) {
            try {
                child = getChildAt(i)
                if (child is AppWidgetHostView) {
                    widgetView = child
                    widgetInfo = widgetView.appWidgetInfo
                    val appWidgetId: Int = widgetView.appWidgetId
                    intent = Intent(LauncherIntent.Notification.NOTIFICATION_IN_VIEWPORT)
                        .setComponent(widgetInfo.provider)
                    intent.putExtra(Extra.EXTRA_APPWIDGET_ID, appWidgetId)
                    intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    context.sendBroadcast(intent)
                }
            } catch (e: Exception) {
            }
        }
    }

    fun onViewportOut() {
        var child: View?
        var widgetView: AppWidgetHostView
        var widgetInfo: AppWidgetProviderInfo
        var intent: Intent
        for (i in childCount - 1 downTo 0) {
            try {
                child = getChildAt(i)
                if (child is AppWidgetHostView) {
                    widgetView = child

                    stopAllAnimationDrawables(widgetView)

                    widgetInfo = widgetView.appWidgetInfo
                    val appWidgetId: Int = widgetView.appWidgetId
                    intent = Intent(LauncherIntent.Notification.NOTIFICATION_OUT_VIEWPORT)
                        .setComponent(widgetInfo.provider)
                    intent.putExtra(Extra.EXTRA_APPWIDGET_ID, appWidgetId)
                    intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    context.sendBroadcast(intent)
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun stopAllAnimationDrawables(vg: ViewGroup) {
        var child: View?
        for (i in vg.childCount - 1 downTo 0) {
            child = vg.getChildAt(i)
            if (child is ImageView) {
                try {
                    val ad: AnimationDrawable = child.drawable as AnimationDrawable
                    ad.stop()
                } catch (e: Exception) {
                }
            } else if (child is ViewGroup) {
                stopAllAnimationDrawables(child)
            }
        }
    }
}