package org.skyphusion.prism.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import org.skyphusion.prism.SecretStoreKeys

/** Home-screen balance glance (iOS PrismWidget). No secrets; text only. */
class BalanceWidgetProvider : AppWidgetProvider() {
  override fun onUpdate(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetIds: IntArray,
  ) {
    val prefs = context.getSharedPreferences(SecretStoreKeys.WIDGET_PREFS, Context.MODE_PRIVATE)
    val balance = prefs.getString(SecretStoreKeys.WIDGET_BALANCE, "Open Prism") ?: "Open Prism"
    val updated = prefs.getString(SecretStoreKeys.WIDGET_UPDATED_AT, "") ?: ""
    for (id in appWidgetIds) {
      val views = RemoteViews(context.packageName, R.layout.widget_balance)
      views.setTextViewText(R.id.widget_balance, balance)
      views.setTextViewText(R.id.widget_updated, updated)
      val launch =
        PendingIntent.getActivity(
          context,
          0,
          Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
      views.setOnClickPendingIntent(R.id.widget_root, launch)
      appWidgetManager.updateAppWidget(id, views)
    }
  }

  companion object {
    fun requestUpdate(context: Context) {
      val mgr = AppWidgetManager.getInstance(context)
      val ids =
        mgr.getAppWidgetIds(ComponentName(context, BalanceWidgetProvider::class.java))
      if (ids.isEmpty()) return
      val intent =
        Intent(context, BalanceWidgetProvider::class.java).apply {
          action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
          putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        }
      context.sendBroadcast(intent)
    }
  }
}
