package org.skyphusion.prism.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Local notifications for long-running media (video gen). */
object NotificationHelper {
  const val CHANNEL_MEDIA = "prism_media"
  private var nextId = 1000

  fun ensureChannels(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val nm = context.getSystemService(NotificationManager::class.java) ?: return
    val ch =
      NotificationChannel(
        CHANNEL_MEDIA,
        "Media generation",
        NotificationManager.IMPORTANCE_DEFAULT,
      ).apply {
        description = "Video / long generation completion"
      }
    nm.createNotificationChannel(ch)
  }

  fun canPost(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 33) return true
    return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
      PackageManager.PERMISSION_GRANTED
  }

  fun notifyMedia(
    context: Context,
    title: String,
    body: String,
    success: Boolean,
  ) {
    ensureChannels(context)
    if (!canPost(context)) return
    val id = nextId++
    val n =
      NotificationCompat.Builder(context, CHANNEL_MEDIA)
        .setSmallIcon(android.R.drawable.ic_menu_gallery)
        .setContentTitle(title)
        .setContentText(body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setAutoCancel(true)
        .setCategory(
          if (success) NotificationCompat.CATEGORY_STATUS
          else NotificationCompat.CATEGORY_ERROR,
        )
        .build()
    try {
      NotificationManagerCompat.from(context).notify(id, n)
    } catch (_: SecurityException) {
      // POST_NOTIFICATIONS denied after check race
    }
  }
}
