package com.dsh.pet

import org.json.JSONArray
import org.json.JSONObject

/**
 * 提醒 / 计划 数据模型。
 *
 * 两类（type）：
 *  · memo   —— 备忘提醒：到点弹气泡，点气泡回提醒列表。无步骤、一次性。
 *  · plan   —— 计划提醒：可带多个步骤（steps），到点弹气泡；逐项勾选，全部完成才算 done。
 *
 * 状态（status）：
 *  · pending    待触发（now < timeMs，或 plan 步骤未完成且已触发过）
 *  · triggered  已触发过（气泡弹过 / 到点了），plan 等待用户逐项勾选
 *  · done       完成（memo 触发过即视为完成；plan 所有步骤勾选完成）
 *
 * 持久化 = Store.reminders.json（独立文件，写坏不影响外观/宠物）。
 * 时间统一用 **epoch 毫秒（Long）** 存，避免日期字符串跨时区/格式问题。
 */
sealed class Reminder {
    abstract val id: String
    abstract val type: String
    abstract val title: String
    abstract val timeMs: Long
    abstract val status: String

    /** 计划步骤：text + 是否勾选。memo 恒为空。 */
    abstract val steps: List<Step>

    /** 重复规则：none / daily / weekly / weekdays / monthly */
    abstract val repeat: String
    /** 提前提醒分钟数（0=到点才提醒）。 */
    abstract val advanceMin: Int

    /** 标签（自由文本，逗号分隔录入，用于归类与搜索）。 */
    abstract val tags: List<String>

    data class Step(val text: String, val done: Boolean)

    data class Memo(
        override val id: String,
        override val title: String,
        override val timeMs: Long,
        override val status: String = STATUS_PENDING,
        val note: String = "",
        val showBubble: Boolean = true,
        val showNotification: Boolean = true,
        override val repeat: String = REPEAT_NONE,
        override val advanceMin: Int = 0,
        override val tags: List<String> = emptyList(),
    ) : Reminder() {
        override val type: String = TYPE_MEMO
        override val steps: List<Step> = emptyList()
    }

    data class Plan(
        override val id: String,
        override val title: String,
        override val timeMs: Long,
        override val status: String = STATUS_PENDING,
        val note: String = "",
        override val steps: List<Step>,
        val showBubble: Boolean = true,
        val showNotification: Boolean = true,
        override val repeat: String = REPEAT_NONE,
        override val advanceMin: Int = 0,
        override val tags: List<String> = emptyList(),
    ) : Reminder() {
        override val type: String = TYPE_PLAN
        val allDone: Boolean get() = steps.isNotEmpty() && steps.all { it.done }
    }

    companion object {
        const val TYPE_MEMO = "memo"
        const val TYPE_PLAN = "plan"
        const val STATUS_PENDING = "pending"
        const val STATUS_TRIGGERED = "triggered"
        const val STATUS_DONE = "done"
        const val REPEAT_NONE = "none"
        const val REPEAT_DAILY = "daily"
        const val REPEAT_WEEKLY = "weekly"
        const val REPEAT_WEEKDAYS = "weekdays"
        const val REPEAT_MONTHLY = "monthly"
        /** 重复规则的中文名（UI 用）。 */
        fun repeatLabel(v: String): String = when (v) {
            REPEAT_DAILY -> "每天"
            REPEAT_WEEKLY -> "每周"
            REPEAT_WEEKDAYS -> "工作日"
            REPEAT_MONTHLY -> "每月"
            else -> "不重复"
        }

        /** 「a, b c」→ 标签列表（去空、去重、保序）。 */
        fun parseTags(raw: String): List<String> =
            raw.split(',', '，', ' ', '、').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        /** 是否到点弹气泡（默认 true，兼容旧数据）。 */
        fun showBubble(r: Reminder): Boolean =
            (r as? Memo)?.showBubble ?: (r as? Plan)?.showBubble ?: true
        /** 是否到点发系统通知（默认 true，兼容旧数据）。 */
        fun showNotification(r: Reminder): Boolean =
            (r as? Memo)?.showNotification ?: (r as? Plan)?.showNotification ?: true

        /**
         * 从 reminders.json 读全部（按 timeMs 升序）。文件缺失/损坏返回空列表（不抛）。
         */
        fun load(raw: String): List<Reminder> {
            if (raw.isBlank()) return emptyList()
            return try {
                val arr = JSONArray(raw)
                val out = ArrayList<Reminder>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    out.add(parse(o))
                }
                out.sortedWith(compareBy({ it.timeMs }, { it.id }))
            } catch (e: Exception) {
                emptyList()
            }
        }

        /** 序列化全部（按 timeMs 升序）写回 JSON 字符串。 */
        fun save(list: List<Reminder>): String {
            val arr = JSONArray()
            for (r in list.sortedWith(compareBy({ it.timeMs }, { it.id }))) {
                arr.put(toJson(r))
            }
            return arr.toString()
        }

        private fun parse(o: JSONObject): Reminder {
            val id = o.optString("id", "").ifEmpty { "r_${System.nanoTime()}" }
            val title = o.optString("title", "")
            val timeMs = o.optLong("timeMs", 0L)
            val status = o.optString("status", STATUS_PENDING)
            val note = o.optString("note", "")
            val type = o.optString("type", TYPE_MEMO)
            val stepsArr = o.optJSONArray("steps")
            val steps = ArrayList<Step>()
            if (stepsArr != null) {
                for (i in 0 until stepsArr.length()) {
                    val s = stepsArr.optJSONObject(i) ?: continue
                    steps.add(Step(s.optString("text", ""), s.optBoolean("done", false)))
                }
            }
            val showBubble = o.optBoolean("showBubble", true)
            val showNotification = o.optBoolean("showNotification", true)
            val repeat = o.optString("repeat", REPEAT_NONE)
            val advanceMin = o.optInt("advanceMin", 0)
            val tags = ArrayList<String>()
            o.optJSONArray("tags")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val t = arr.optString(i, "").trim()
                    if (t.isNotEmpty()) tags.add(t)
                }
            }
            return if (type == TYPE_PLAN && steps.isNotEmpty()) {
                Plan(id, title, timeMs, status, note, steps, showBubble, showNotification, repeat, advanceMin, tags)
            } else {
                Memo(id, title, timeMs, status, note, showBubble, showNotification, repeat, advanceMin, tags)
            }
        }

        private fun toJson(r: Reminder): JSONObject {
            val o = JSONObject().put("id", r.id).put("type", r.type)
                .put("title", r.title).put("timeMs", r.timeMs).put("status", r.status)
            val memo = r as? Memo
            val plan = r as? Plan
            o.put("note", (memo?.note ?: plan?.note ?: ""))
            o.put("showBubble", (memo?.showBubble ?: plan?.showBubble ?: true))
            o.put("showNotification", (memo?.showNotification ?: plan?.showNotification ?: true))
            o.put("repeat", r.repeat)
            o.put("advanceMin", r.advanceMin)
            if (r.tags.isNotEmpty()) {
                val ta = JSONArray()
                for (t in r.tags) ta.put(t)
                o.put("tags", ta)
            }
            if (plan != null) {
                val a = JSONArray()
                for (s in plan.steps) a.put(JSONObject().put("text", s.text).put("done", s.done))
                o.put("steps", a)
            }
            return o
        }
    }
}
