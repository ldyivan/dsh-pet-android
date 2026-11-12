package com.dsh.pet

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 桌面小组件：备忘录。
 *
 * 结构：标题栏（「备忘录」+ 待办条数）/ 分隔线 / 最多 4 条待办 / 底部分隔线 +「＋ 新建提醒」。
 * 每条为「圆点 + 标题 + 相对时间（今天 / 明天 / 已逾期）」的备忘录式两行布局；
 * 配色全部引用主题色资源，暗色模式自动跟随。不做秒级刷新，避免频繁跨进程更新。
 */
class ReminderWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val views = buildViews(context)
        for (id in appWidgetIds) appWidgetManager.updateAppWidget(id, views)
    }

    companion object {
        /** 数据变化后调用：刷新所有已放置的小组件。 */
        fun refresh(context: Context) {
            val mgr = AppWidgetManager.getInstance(context) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(context, ReminderWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = buildViews(context)
            for (id in ids) mgr.updateAppWidget(id, views)
        }

        private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
        private val DATE_FMT = DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.getDefault())

        /** 相对时间文案 + 是否已逾期。备忘录风格：今天 / 明天 / 已逾期 / 具体日期。 */
        private fun relativeTime(timeMs: Long, now: Long): Pair<String, Boolean> {
            val zone = ZoneId.systemDefault()
            val overdue = timeMs <= now
            val target = Instant.ofEpochMilli(timeMs).atZone(zone).toLocalDateTime()
            val today: LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val day = target.toLocalDate()
            val text = when {
                overdue -> "已逾期"
                day == today -> "今天 " + target.format(TIME_FMT)
                day == today.plusDays(1) -> "明天 " + target.format(TIME_FMT)
                else -> target.format(DATE_FMT)
            }
            return text to overdue
        }

        private fun buildViews(context: Context): RemoteViews {
            val rv = RemoteViews(context.packageName, R.layout.widget_reminders)
            rv.removeAllViews(R.id.widget_rows)
            val now = System.currentTimeMillis()
            val all = try {
                Reminder.load(Store.readReminders(context))
            } catch (e: Exception) {
                emptyList()
            }
            val pending = all.filter { it.status != Reminder.STATUS_DONE && it.timeMs > 0 }
                .sortedBy { it.timeMs }
            val items = pending.take(4)

            rv.setTextViewText(
                R.id.widget_count,
                if (pending.isEmpty()) "已清空" else "${pending.size} 条",
            )
            if (items.isEmpty()) {
                rv.setViewVisibility(R.id.widget_empty, View.VISIBLE)
            } else {
                rv.setViewVisibility(R.id.widget_empty, View.GONE)
                for (r in items) {
                    val (timeText, overdue) = relativeTime(r.timeMs, now)
                    val row = RemoteViews(context.packageName, R.layout.widget_row)
                    row.setTextViewText(R.id.row_title, r.title)
                    row.setTextViewText(R.id.row_time, timeText)
                    // 逾期：圆点与时间文字变红，一眼能挑出来
                    row.setTextColor(
                        R.id.row_dot,
                        context.getColor(if (overdue) R.color.dsh_danger else R.color.dsh_accent),
                    )
                    row.setTextColor(
                        R.id.row_time,
                        context.getColor(if (overdue) R.color.dsh_danger else R.color.dsh_text_hint),
                    )
                    rv.addView(R.id.widget_rows, row)
                }
            }

            val open = Intent(context, SettingsActivity::class.java).apply {
                putExtra(SettingsActivity.EXTRA_REMINDER_TAB, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pi = PendingIntent.getActivity(
                context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            rv.setOnClickPendingIntent(R.id.widget_root, pi)
            rv.setOnClickPendingIntent(R.id.widget_footer, pi)
            return rv
        }
    }
}