package com.dsh.pet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 提醒精确闹钟的广播接收器：到点被系统叫醒 → 拉起常驻 [PetService] fire 弹气泡。
 *
 * 设计取舍：
 *  · 这里**不做重活**，只转手 startService（带 id 列表），真正的「改状态 + 弹气泡」
 *    在 PetService（它持有 WebView + 通知渠道，且是前台服务，最不容易被 ROM 杀掉）。
 *  · 进程被杀也能被这条广播唤醒：startForegroundService 会重新拉起整个应用进程。
 *  · 若该 id 此刻状态已不是 pending（用户已提前完成/删除），service 侧 fire 会按
 *    最新落盘状态判定，不会重复弹。
 */
class ReminderAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 「稍后提醒」：把该条时间推到 now + N 分钟并重新挂闹钟（不改触发状态）
        if (intent.action == ACTION_SNOOZE) {
            val id = intent.getStringExtra(EXTRA_ID) ?: return
            val min = intent.getIntExtra(EXTRA_SNOOZE_MIN, 10).coerceIn(1, 1440)
            val list = Reminder.load(Store.readReminders(context)).map { r ->
                if (r.id != id) r else when (r) {
                    is Reminder.Memo -> r.copy(
                        timeMs = System.currentTimeMillis() + min * 60_000L,
                        status = Reminder.STATUS_PENDING)
                    is Reminder.Plan -> r.copy(
                        timeMs = System.currentTimeMillis() + min * 60_000L,
                        status = Reminder.STATUS_PENDING)
                }
            }
            Store.writeReminders(context, Reminder.save(list))
            ReminderScheduler.armAll(context)
            Log.i("dsh-pet", "提醒稍后 $min 分钟: $id")
            return
        }
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        Log.i("dsh-pet", "提醒闹钟到点: $id → 拉起服务 fire")
        val service = Intent(context, PetService::class.java).apply {
            action = PetService.ACTION_REMINDER_FIRE
            putExtra(PetService.EXTRA_REMINDER_IDS, arrayOf(id))
        }
        try {
            context.startForegroundService(service)
        } catch (e: Exception) {
            context.startService(service)
        }
    }

    companion object {
        const val ACTION = "com.dsh.pet.action.REMINDER_ALARM"
        /** 通知「稍后提醒」按钮的广播。 */
        const val ACTION_SNOOZE = "com.dsh.pet.action.REMINDER_SNOOZE"
        const val EXTRA_ID = "reminder_id"
        const val EXTRA_SNOOZE_MIN = "snooze_min"
    }
}