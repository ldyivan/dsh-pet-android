package com.dsh.pet

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/**
 * 提醒/计划 触发调度。
 *
 * 计时中枢挂在常驻的 [PetService] 上（服务活着才弹气泡），每个待触发项用
 * AlarmManager 精确闹钟到点唤醒；服务被 ROM 杀掉后由 [ReminderAlarmReceiver]
 * 的 PendingIntent 把服务拉起来再弹。
 *
 * 状态流转（见 [Reminder]）：
 *  · memo 到点  → done（一次性，弹过气泡即完成）
 *  · plan 到点  → triggered（弹气泡，等用户逐项勾选；全勾后由 UI 置 done）
 *
 * 提醒列表每次增删改后，UI 侧调 [armAll] 重挂；停止桌宠时 [cancelAll]。
 */
object ReminderScheduler {

    /** 闹钟 requestCode 基址（+ 条目序号），避免与系统/看门狗闹钟撞号。 */
    private const val REQ_BASE = 47000

    /**
     * 重新挂全部待触发项。调用时机：服务启动、提醒新增/改时/删后。
     *  · 未到点的 pending → 挂精确闹钟；
     *  · 已到点但仍 pending（离线错过的）→ 立即触发（交给 service fire 处理）。
     */
    fun armAll(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val list = Reminder.load(Store.readReminders(ctx))
        val now = System.currentTimeMillis()
        var dueFired = false
        for ((idx, r) in list.withIndex()) {
            if (r.status == Reminder.STATUS_DONE) continue
            // 重复提醒：若已过点则先顺延到下一次（避免「昨天的每日提醒」一进 App 就狂弹）
            var cur = r
            if (cur.repeat != Reminder.REPEAT_NONE && cur.timeMs <= now &&
                cur.status == Reminder.STATUS_PENDING) {
                cur = cur.withTimeMs(nextOccurrence(cur.timeMs, cur.repeat, now))
            }
            if (cur.timeMs > now) {
                // 提前提醒：时间够则额外挂一个「提前 N 分钟」的闹钟（用负偏移 requestCode 区分）
                if (cur.advanceMin > 0) {
                    val advAt = cur.timeMs - cur.advanceMin * 60_000L
                    if (advAt > now) schedule(am, ctx, cur.id, advAt, idx + ADVANCE_OFFSET)
                }
                schedule(am, ctx, cur.id, cur.timeMs, idx)
            } else {
                dueFired = true // 到点/已过，下面统一 fire
            }
        }
        // 顺延后的时间要落盘（否则每次 armAll 都重算）
        val listNow = Reminder.load(Store.readReminders(ctx))
        val bumped = listNow.map { r ->
            if (r.repeat != Reminder.REPEAT_NONE && r.status == Reminder.STATUS_PENDING && r.timeMs <= now)
                r.withTimeMs(nextOccurrence(r.timeMs, r.repeat, now)) else r
        }
        if (bumped != listNow) Store.writeReminders(ctx, Reminder.save(bumped))
        // 到点/过点的 pending 立即触发（服务若在跑直接弹；不在跑靠拉起来弹）
        if (dueFired) {
            val targets = bumped.filter { it.status == Reminder.STATUS_PENDING && it.timeMs <= now && it.repeat == Reminder.REPEAT_NONE }
            if (targets.isNotEmpty()) {
                val i = Intent(ctx, PetService::class.java).apply {
                    action = PetService.ACTION_REMINDER_FIRE
                    putExtra(PetService.EXTRA_REMINDER_IDS, targets.map { it.id }.toTypedArray())
                }
                try {
                    ctx.startForegroundService(i)
                } catch (e: Exception) {
                    ctx.startService(i)
                }
            }
        }
    }

    /** 提前提醒用的 requestCode 偏移（与正常闹钟错开，避免相互覆盖）。 */
    private const val ADVANCE_OFFSET = 2000

    /** 造一个只改 timeMs 的副本。 */
    private fun Reminder.withTimeMs(t: Long): Reminder = when (this) {
        is Reminder.Memo -> copy(timeMs = t)
        is Reminder.Plan -> copy(timeMs = t)
    }

    /**
     * 计算下一次触发时间（严格大于 now）。
     * daily=+1天；weekly=+7天；weekdays=+1天但跳过周六日；monthly=+1个月（按当地时区/日历）。
     */
    fun nextOccurrence(fromMs: Long, repeat: String, now: Long): Long {
        if (repeat == Reminder.REPEAT_NONE) return fromMs
        val zone = java.time.ZoneId.systemDefault()
        var t = java.time.Instant.ofEpochMilli(fromMs).atZone(zone).toLocalDateTime()
        var guard = 0
        while (t.atZone(zone).toInstant().toEpochMilli() <= now && guard++ < 500) {
            t = when (repeat) {
                Reminder.REPEAT_DAILY -> t.plusDays(1)
                Reminder.REPEAT_WEEKLY -> t.plusWeeks(1)
                Reminder.REPEAT_WEEKDAYS -> {
                    var n = t.plusDays(1)
                    while (n.dayOfWeek.value >= 6) n = n.plusDays(1) // 6=周六 7=周日
                    n
                }
                Reminder.REPEAT_MONTHLY -> t.plusMonths(1)
                else -> return fromMs
            }
        }
        return t.atZone(zone).toInstant().toEpochMilli()
    }

    /** 挂一条精确闹钟（到点由 [ReminderAlarmReceiver] 接 → 拉起服务 fire）。 */
    private fun schedule(am: AlarmManager, ctx: Context, id: String, timeMs: Long, idx: Int) {
        val i = Intent(ctx, ReminderAlarmReceiver::class.java).apply {
            action = ReminderAlarmReceiver.ACTION
            putExtra(ReminderAlarmReceiver.EXTRA_ID, id)
        }
        val pi = PendingIntent.getBroadcast(
            ctx, REQ_BASE + (idx % 500), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timeMs, pi)
        } catch (e: Exception) {
            // API 31+ 受限机型可能拒绝 setExact：退到 set（不精确但能到）
            try {
                am.set(AlarmManager.RTC_WAKEUP, timeMs, pi)
            } catch (_: Exception) { }
        }
    }

    /** 某条状态被外部（UI 勾选/删除/标记完成）改动后，取消它挂着的闹钟。 */
    fun cancel(ctx: Context, id: String) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val list = Reminder.load(Store.readReminders(ctx))
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        val i = Intent(ctx, ReminderAlarmReceiver::class.java).apply {
            action = ReminderAlarmReceiver.ACTION
            putExtra(ReminderAlarmReceiver.EXTRA_ID, id)
        }
        val pi = PendingIntent.getBroadcast(
            ctx, REQ_BASE + (idx % 500), i,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        pi?.cancel()
    }

    /** 停止桌宠时清空所有提醒闹钟（保留数据，只取消计时）。 */
    fun cancelAll(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val list = Reminder.load(Store.readReminders(ctx))
        for ((idx, r) in list.withIndex()) {
            if (r.status == Reminder.STATUS_DONE) continue
            val i = Intent(ctx, ReminderAlarmReceiver::class.java).apply {
                action = ReminderAlarmReceiver.ACTION
                putExtra(ReminderAlarmReceiver.EXTRA_ID, r.id)
            }
            val pi = PendingIntent.getBroadcast(
                ctx, REQ_BASE + (idx % 500), i,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            pi?.cancel()
        }
    }

    /**
     * 到点触发：把 ids 中仍为 pending 且已过点的项按类型流转（memo→done，plan→triggered）并落盘，
     * 返回**本次实际触发**的项（带新状态，供弹气泡/通知）。幂等：已 done/triggered 的不会重复触发。
     */
    fun fireDue(ctx: Context, ids: List<String>): List<Reminder> {
        val now = System.currentTimeMillis()
        val set = ids.toSet()
        val loaded = Reminder.load(Store.readReminders(ctx))
        val fired = ArrayList<Reminder>()
        val updated = loaded.map { r ->
            if (r.id in set && r.status == Reminder.STATUS_PENDING && r.timeMs <= now) {
                // 重复提醒：弹完这次后自动顺延到下一次，保持 pending（不会变 done）
                if (r.repeat != Reminder.REPEAT_NONE) {
                    val next = nextOccurrence(r.timeMs, r.repeat, now)
                    val nr = r.withTimeMs(next)
                    fired.add(r)
                    nr
                } else {
                    val nr = when (r) {
                        is Reminder.Plan -> r.copy(status = Reminder.STATUS_TRIGGERED)
                        is Reminder.Memo -> r.copy(status = Reminder.STATUS_DONE)
                    }
                    fired.add(nr)
                    nr
                }
            } else r
        }
        if (fired.isNotEmpty()) Store.writeReminders(ctx, Reminder.save(updated))
        return fired
    }

    /**
     * 落盘一条提醒的状态变更（UI 勾选步骤 / 标记完成 / 删除后都调）。
     * 返回更新后的完整列表（供 UI 刷新），并取消该条对应闹钟。
     */
    fun updateStatus(ctx: Context, id: String, status: String): List<Reminder> {
        applyStatus(ctx) { r -> if (r.id == id) r.withStatus(status) else r }
        cancel(ctx, id)
        return Reminder.load(Store.readReminders(ctx))
    }

    /**
 * 勾选/取消某条 plan 的某一步。
 *
 * **依次完成**语义（需求：多步骤需按顺序勾选，最后才完成）：
 *  · 推进：只有"当前第一个未完成步骤"（nextIdx）能被勾上——不允许跳步勾后面的；
 *  · 反悔：已完成的步骤（index < nextIdx）可以取消，取消后 nextIdx 前移；
 *  · 全勾 → status 自动 done，否则 triggered（等待继续勾选）。
 *  · 点一个"尚未轮到的步骤"（index > nextIdx）→ 忽略（保持顺序）。
 */
fun toggleStep(ctx: Context, id: String, stepIndex: Int): List<Reminder> {
    applyStatus(ctx) { r ->
        val plan = r as? Reminder.Plan ?: return@applyStatus r
        if (stepIndex < 0 || stepIndex >= plan.steps.size) return@applyStatus r
        val nextIdx = plan.steps.indexOfFirst { !it.done } // -1 = 全部已完成
        val cur = plan.steps[stepIndex]
        val canAdvance = (nextIdx == stepIndex) && !cur.done  // 该勾的就是第一个未完成的
        val canUndo = nextIdx != -1 && stepIndex < nextIdx && cur.done // 反悔取消已完成的
        if (!canAdvance && !canUndo) return@applyStatus r // 跳步 / 重复勾：忽略
        val newSteps = plan.steps.mapIndexed { i, s ->
            if (i == stepIndex) s.copy(done = !s.done) else s
        }
        val allDone = newSteps.isNotEmpty() && newSteps.all { it.done }
        val status = if (allDone) Reminder.STATUS_DONE else Reminder.STATUS_TRIGGERED
        plan.copy(steps = newSteps, status = status)
    }
    return Reminder.load(Store.readReminders(ctx))
}

    /** 计划一次性勾完所有步骤（落盘 done）。 */
    fun markAllStepsDone(ctx: Context, id: String): List<Reminder> {
        applyStatus(ctx) { r ->
            val plan = r as? Reminder.Plan ?: return@applyStatus r
            if (plan.steps.isEmpty()) return@applyStatus r
            plan.copy(
                steps = plan.steps.map { it.copy(done = true) },
                status = Reminder.STATUS_DONE,
            )
        }
        return Reminder.load(Store.readReminders(ctx))
    }

    /** 删除一条（落盘 + 取消闹钟）。 */
    fun remove(ctx: Context, id: String): List<Reminder> {
        val list = Reminder.load(Store.readReminders(ctx)).filter { it.id != id }
        Store.writeReminders(ctx, Reminder.save(list))
        cancel(ctx, id)
        return list
    }

    /** 通用：改一条再整表落盘。transform 返回该条的新形态（或原样表示不变）。 */
    private fun applyStatus(ctx: Context, transform: (Reminder) -> Reminder): List<Reminder> {
        val list = Reminder.load(Store.readReminders(ctx)).map(transform)
        Store.writeReminders(ctx, Reminder.save(list))
        return list
    }

    /** 二次编辑某条（标题/时间/气泡开关/通知栏开关/步骤文本）：按 id 限定作用域，落盘 + 取消该条旧闹钟 + armAll 重挂。 */
    fun update(ctx: Context, id: String, transform: (Reminder) -> Reminder): List<Reminder> {
        val list = Reminder.load(Store.readReminders(ctx)).map { if (it.id == id) transform(it) else it }
        Store.writeReminders(ctx, Reminder.save(list))
        // 改时间/内容后：取消该条旧闹钟，armAll 统一重挂（幂等）
        cancel(ctx, id)
        armAll(ctx)
        return list
    }

    /** data 不可变 → 造一个改了 status 的副本（Memo/Plan 各一份）。 */
    private fun Reminder.withStatus(s: String): Reminder = when (this) {
        is Reminder.Memo -> copy(status = s)
        is Reminder.Plan -> copy(status = s)
    }
}
