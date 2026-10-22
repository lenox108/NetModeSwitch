package com.netmode.switcher

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import android.widget.RemoteViewsService

/**
 * The rows of the rule list widget before Android 12, which cannot hand a list its rows
 * directly (RemoteCollectionItems): the launcher asks this service for them instead. The rows
 * are the very ones [RulesWidget.rows] builds from Android 12.
 */
class RulesWidgetService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(
        applicationContext,
        intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID),
        intent.getBooleanExtra(EXTRA_SHORT, false),
    )

    private class Factory(val ctx: Context, val id: Int, val short: Boolean) : RemoteViewsFactory {
        private var rows: List<Pair<Long, RemoteViews>> = emptyList()

        override fun onCreate() = Unit
        override fun onDestroy() = Unit

        override fun onDataSetChanged() {
            // Under the widgets' lock: the colour mapping is shared with painting (Widgets.palette).
            rows = synchronized(Widgets) {
                Widgets.palette(id)
                RulesWidget.rows(ctx, id, Widgets.Snap(), short)
            }
        }

        override fun getCount() = rows.size
        override fun getViewAt(position: Int): RemoteViews =
            rows.getOrNull(position)?.second ?: RemoteViews(ctx.packageName, R.layout.widget_rule_item)
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = rows.getOrNull(position)?.first ?: position.toLong()
        override fun hasStableIds() = true
    }

    companion object {
        private const val EXTRA_SHORT = "short"

        /** Distinct per widget and width, or the launcher would share one factory between them. */
        fun intent(ctx: Context, id: Int, short: Boolean): Intent =
            Intent(ctx, RulesWidgetService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(EXTRA_SHORT, short)
                .setData(Uri.parse("netmode://rules/$id/$short"))
    }
}
