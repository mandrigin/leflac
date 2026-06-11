package app.nogarbo.leflac.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import app.nogarbo.leflac.GlyphToyActivity
import app.nogarbo.leflac.R

class GlyphToyWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }
}

internal fun updateAppWidget(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetId: Int
) {
    // Launch GlyphToyActivity on click
    val intent = Intent(context, GlyphToyActivity::class.java)
    val pendingIntent = PendingIntent.getActivity(
        context,
        0,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val views = RemoteViews(context.packageName, R.layout.glyph_toy_widget)
    views.setOnClickPendingIntent(R.id.widget_container, pendingIntent)

    appWidgetManager.updateAppWidget(appWidgetId, views)
}
