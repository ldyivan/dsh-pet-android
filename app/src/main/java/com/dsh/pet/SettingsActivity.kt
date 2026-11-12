package com.dsh.pet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.widget.FrameLayout
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.widget.CheckBox
import android.view.MotionEvent
import android.graphics.Canvas
import android.graphics.Paint
import android.widget.NumberPicker
import java.text.SimpleDateFormat
import java.util.Locale
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import org.json.JSONArray
import org.json.JSONObject
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Window
import android.widget.ProgressBar
import java.io.File

/**
 * 设置页（唯一可见 Activity）。
 *
 * 职责：
 *  1. 申请悬浮窗 / 通知权限（桌宠必需），启动 / 停止 / 显示 / 隐藏桌宠；
 *  2. **外观**：显示大小、透明度、宠物数量；
 *  3. **互动**：点击音效开关、点击台词库（每行一句）；
 *  4. **AI**：OpenAI 兼容参数，改完自动保存；
 *  5. 许可与素材说明。
 *
 * 两个设计原则（改这里之前先读）：
 *  · **改完立刻生效**：外观/互动改动直接写 ui-settings.json 并调 PetService.reloadConfig()，
 *    不设「保存」按钮——手机上没有第二块屏幕能对照预览，让用户点保存再回去看，等于让他记住旧样子。
 *  · **AI 设置自动保存 + 去抖**：输入过程中每 600ms 落一次盘；漏点「保存」是之前
 *    「API 设置不持久」最可能的直接原因，不能靠用户记性。
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        /** 启动时携带「进提醒 tab」标记（提醒气泡/通知点击跳列表用） */
        const val EXTRA_REMINDER_TAB = "com.dsh.pet.extra.REMINDER_TAB"
        /** 定位到某条提醒（滚动 + 高亮）；为空则定位到列表头 */
        const val EXTRA_FOCUS_REMINDER_ID = "com.dsh.pet.extra.FOCUS_REMINDER_ID"
    }

    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var powerBtn: Button
    private lateinit var hideBtn: Button
    private lateinit var overlayBtn: Button
    private lateinit var notifyBtn: Button
    // 后台保活
    private lateinit var batteryBtn: Button
    private lateinit var autoStartBtn: Button
    private lateinit var keepAliveHint: TextView
    /** 「从最近任务隐藏」开关（防误删后台，com.dshpet.android 1.8.1 同款 setExcludeFromRecents 机制） */
    private lateinit var hideTaskSwitch: Switch
    /** 「台词库含内置默认」组合开关：勾选 = 自定义 + 默认库组合；不勾选 = 仅自定义（留空仍自动用默认库） */
    private lateinit var sayingsCombineSwitch: Switch
    // ---- 提醒 tab（备忘 / 计划）----
    private lateinit var settingsTabScroll: ScrollView
    private lateinit var contentFrame: FrameLayout
    private lateinit var reminderTabRoot: LinearLayout
    private lateinit var reminderTabScroll: ScrollView
    private lateinit var reminderListContainer: LinearLayout
    private lateinit var reminderEmpty: TextView
    /** 首次操作提示条（左滑/长按提示，用过后自动隐藏）。 */
    private var reminderHintBar: TextView? = null
    /** 免打扰时段摘要文本（滚轮弹窗保存后回写，避免整页重载）。 */
    private var quietSummaryView: TextView? = null
    /** 底部悬浮提示层（删除撤销条等），叠在内容区之上、tab 栏之上不遮挡 tab。 */
    private var snackOverlay: android.widget.FrameLayout? = null
    /** 当前显示的撤销条（新的覆盖旧的，避免叠加）。 */
    private var snackBar: View? = null

    /** 空状态宿主（插画 + 文案），整体显隐。 */
    private var reminderEmptyHost: LinearLayout? = null
    /** 已完成项分组是否展开（默认折叠）。 */
    private var doneGroupExpanded = false
    /** 搜索关键词（标题/步骤/标签模糊匹配）。 */
    private var reminderQuery = ""
    /** 排序：time_asc / time_desc / title */
    private var reminderSort = "time_asc"
    /** 删除撤销缓存：最近一次删除的整表快照（用于撤销）。 */
    private var undoSnapshot: String? = null
    private var undoTimer: Runnable? = null
    private lateinit var tabReminderBtn: Button
    private lateinit var tabSettingsBtn: Button
    private var activeTabIsReminder = false
    private var reminderFocusId: String? = null
    // 提醒倒计时：当前列表每项 (标签, 目标时间ms)，cdTick 每秒刷新文案（<12h 精确到秒）
    private val countdownLabels = ArrayList<TextView>()
    private val countdownTargets = ArrayList<Long>()
    private val cdTick = object : Runnable {
        private var tickCount = 0L
        override fun run() {
            if (countdownLabels.isEmpty()) return
            tickCount++
            val nowMs = System.currentTimeMillis()
            for (i in countdownLabels.indices) {
                val cd = countdownLabels[i]
                val target = countdownTargets[i]
                val left = target - nowMs
                // 节流：只有 <12h 的项需要秒级刷新；≥12h 的文案一分钟才变一次，30 秒刷一次即可（省 CPU）
                if (left >= 12L * 3600 * 1000 && tickCount % 30L != 1L) continue
                cd.text = countdownText(target, nowMs)
                // 不足 1 小时变强调色，提醒感更强
                cd.setTextColor(if (left < 3600000L)
                    colorRes(R.color.dsh_accent) else colorRes(R.color.dsh_text_hint))
            }
            main.postDelayed(this, 1000L)
        }
    }

    private lateinit var sizeSeek: SeekBar
    private lateinit var sizeVal: TextView
    private lateinit var opacitySeek: SeekBar
    private lateinit var opacityVal: TextView
    private lateinit var trustedSwitch: Switch
    private lateinit var trustedHint: TextView
    /** Android 12+ 才有的「上限 xx% 是系统限制」说明，低版本没有这块 */
    private var opacityHint: TextView? = null
    /** 当前实际透明度（opacityMin~1.0）：滑块量程变化时用它反推位置 */
    private var opacityValue: Double = 1.0
    private lateinit var countSeek: SeekBar
    private lateinit var countVal: TextView
    private lateinit var soundSwitch: Switch
    private lateinit var sayingsEdit: EditText

    private lateinit var aiEnabled: Switch
    private lateinit var baseUrlEdit: EditText
    private lateinit var modelEdit: EditText
    private lateinit var apiKeyEdit: EditText
    private lateinit var aiHint: TextView

    // 关于与更新
    private lateinit var updateBtn: Button
    private lateinit var releaseBtn: Button
    private lateinit var updateHint: TextView
    /** 检查中：按钮置灰，避免连点把 GitHub 限额打满 */
    private var checkingUpdate = false
    // 崩溃日志闭环：启动时若有崩溃日志则自动弹窗展示，可复制(并清空)/手动清空

    private val main = Handler(Looper.getMainLooper())
    private val REQ_NOTIFY = 1001
    private val REQ_OVERLAY = 1002
    private val REQ_EXPORT_REMINDERS = 1003
    private val REQ_IMPORT_REMINDERS = 1004
    /** 导出文本类文件（Markdown / ics）：内容先存字段，选完文件后写入。 */
    private val REQ_EXPORT_TEXT = 1005
    private var pendingExportText: String = ""
    /** 一次性提示条的偏好键（存 ui-settings.json 的 flags）。 */
    private val KEY_REMINDER_HINT_SHOWN = "reminderHintShown"

    /** 加载配置期间不要触发「改动即保存」 */
    private var loading = true

    /** 本次页面生命周期内是否已经弹过通知权限系统弹窗（弹过一次后只能去设置页） */
    private var notifyAsked = false

    // 显示大小：视口宽度的 40% ~ 90%（与 host.ts 的 PET_RATIO_MIN/MAX 对齐）
    private val sizeMin = 0.40
    private val sizeMax = 0.90
    private val opacityMin = 0.30

    /**
     * 系统允许的遮挡上限。
     *
     * Android 12 起窗口 alpha 不能越过 Settings.Global 的
     * `maximum_obscuring_opacity_for_touch`（默认 0.8），越过之后落在桌面上的触摸会被
     * InputDispatcher 判成「不可信」直接丢弃——所以普通悬浮窗的透明度只能压到 ~78%。
     * 留 2% 余量，避免临界值抖动。
     */
    private fun systemOpacityMax(): Double {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 1.00
        val fallback = 0.8f
        val raw = try {
            Settings.Global.getFloat(contentResolver, "maximum_obscuring_opacity_for_touch", fallback)
        } catch (e: Exception) {
            fallback
        }
        return (raw - 0.02f).coerceIn(0.30f, 1.0f).toDouble()
    }

    /** 「完全体模式」开关是否已打开（ui-settings.json 的 trustedOverlay） */
    private fun trustedRequested(): Boolean = try {
        JSONObject(Store.readUiSettings(this)).optBoolean("trustedOverlay", false)
    } catch (e: Exception) {
        false
    }

    /** 完全体模式是否真的生效：开关打开 **且** 无障碍服务已连接 */
    private fun trustedActive(): Boolean = trustedRequested() && PetA11yService.isEnabled()

    /**
     * 透明度滑块的**真实**量程上限。
     *
     * 普通模式 = 系统遮挡阈值（约 78%）；完全体模式下窗口是可信覆盖层，不受该阈值约束，
     * 直接给到 100%。用 getter 而不是 lazy —— 用户随时可能开关无障碍，量程必须跟着变。
     */
    private val opacityMax: Double
        get() = if (trustedActive()) 1.00 else systemOpacityMax()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        loadUiSettings()
        loadAiSettings()
        loading = false
        refreshPermState()
        // 应用「隐藏最近任务」持久化状态（用户之前开过开关，重启后仍不进最近任务）
        if (KeepAlive.isHiddenFromRecents(this)) {
            KeepAlive.applyHideFromRecents(this, true)
        }
        // 进页面先静默查一次：用户不用点也知道有没有新版（失败只写小字，不弹窗）
        main.postDelayed({ checkUpdate(true) }, 300L)
        // 崩溃日志闭环：启动时若有日志自动弹窗，无日志不弹
        if (readCrashLog().isNotBlank()) {
            main.postDelayed({ showCrashDialog() }, 600L)
        }
    }

    /** 已在运行（后台）时被气泡/通知/小组件再次拉起：切到提醒 tab，而不是仍停在设置页。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_REMINDER_TAB, false)) {
            reminderFocusId = intent.getStringExtra(EXTRA_FOCUS_REMINDER_ID)
            switchTab(true)
            refreshReminderList()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermState()
        // 用户可能刚从系统无障碍页回来：量程与文案要立刻跟着变
        refreshTrustedUi()
        // 状态可能被通知栏改掉（隐藏/显示、服务被系统回收），本页在前台时就该跟着变：
        // 之前只在 onResume 刷一次，用户从通知栏隐藏再切回来，按钮文案还是旧的。
        main.removeCallbacks(statePoll)
        main.postDelayed(statePoll, 400L)
    }

    override fun onPause() {
        main.removeCallbacks(statePoll)
        super.onPause()
    }

    /** 前台状态轮询：让页面上的文案/配色永远等于服务的真实状态 */
    private val statePoll = object : Runnable {
        override fun run() {
            refreshPermState()
            main.postDelayed(this, 800L)
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI

    /** 运行时取色：随系统浅色/深色自动选 values/ 或 values-night/ 色板（暗黑适配）。 */
    private fun colorRes(id: Int): Int =
        androidx.core.content.ContextCompat.getColor(this, id)

    private val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }

    private fun buildUi(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(32))
            setBackgroundColor(colorRes(R.color.dsh_bg))
        }

        // ---------------- 头部 ----------------
        root.addView(TextView(this).apply {
            text = getString(R.string.settings_title)
            textSize = 26f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(colorRes(R.color.dsh_text_primary))
        })
        root.addView(TextView(this).apply {
            text = "蓝色大肥鱼 · 桌面宠物"
            textSize = 13f
            setTextColor(colorRes(R.color.dsh_text_hint))
            setPadding(0, dp(4), 0, dp(2))
        })

        // ---------------- 运行状态 ----------------
        root.addView(card {
            val row = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            statusDot = View(this@SettingsActivity).apply {
                background = roundRect(colorRes(R.color.dsh_dot_inactive), 99, 0)
            }
            row.addView(statusDot, LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(8) })
            statusText = TextView(this@SettingsActivity).apply {
                textSize = 14f
                setTextColor(colorRes(R.color.dsh_text_secondary))
            }
            row.addView(statusText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(row)

            // 启停融合成一个按钮：同一位置按状态切换语义，不再让用户在「启动」和「停止」之间找。
            // 状态由 refreshPermState() 每 800ms 依据 PetService.running 重刷。
            powerBtn = pill("启动桌宠", true) { onPowerClick() }
            addView(
                powerBtn,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(12) },
            )

            hideBtn = pill("暂时隐藏桌宠", false) { toggleHide() }
            addView(
                hideBtn,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) },
            )

            val row2 = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, 0)
            }
            overlayBtn = pill(getString(R.string.settings_perm_overlay), false) { openOverlaySettings() }
            notifyBtn = pill(getString(R.string.settings_perm_notify), false) { openNotifySettings() }
            row2.addView(overlayBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row2.addView(
                notifyBtn,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) },
            )
            addView(row2)
        })

        // ---------------- 后台保活 ----------------
        root.addView(card {
            addView(sectionTitle("后台保活"))
            addView(desc("保持后台运行，防止被系统清理。"))

            batteryBtn = pill("电池优化", false) { KeepAlive.requestIgnoreBattery(this@SettingsActivity) }
            addView(
                batteryBtn,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(10) },
            )

            autoStartBtn = pill("自启动管理（部分机型）", false) { KeepAlive.openAutoStart(this@SettingsActivity) }
            addView(
                autoStartBtn,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) },
            )

            // 「从最近任务隐藏」开关：开启后本应用卡片不进最近任务列表，防误划掉后台
            // （桌宠在桌面常显，唯一可见界面是设置页，藏掉就不容易被误删）
            hideTaskSwitch = Switch(this@SettingsActivity).apply {
                text = "从最近任务隐藏（防误删后台）"
                textSize = 14f
                setTextColor(colorRes(R.color.dsh_text_secondary))
                setPadding(0, dp(8), 0, dp(2))
                isChecked = KeepAlive.isHiddenFromRecents(this@SettingsActivity)
                setOnCheckedChangeListener { _, checked ->
                    if (loading) return@setOnCheckedChangeListener
                    KeepAlive.setHiddenFromRecents(this@SettingsActivity, checked)
                    KeepAlive.applyHideFromRecents(this@SettingsActivity, checked)
                    if (checked) {
                        Toast.makeText(
                            this@SettingsActivity,
                            "已隐藏：本应用卡片不再出现在最近任务，可从设置页随时回来",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
            addView(hideTaskSwitch)

            keepAliveHint = desc("")
            addView(keepAliveHint)
        })

        // ---------------- 外观 ----------------
        root.addView(card {
            addView(sectionTitle("外观"))
            addView(desc("调整即时生效，无需保存。"))

            sizeVal = valueLabel()
            addView(labelRow("显示大小", sizeVal))
            sizeSeek = slider(100)
            addView(sizeSeek)

            opacityVal = valueLabel()
            addView(labelRow("透明度", opacityVal))
            opacitySeek = slider(100)
            addView(opacitySeek)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // 如实告知：不是没生效，是系统不允许（见 PetService.obscuringAlphaLimit 注释）。
                // 数字用实时阈值，不再写死 78%（不同 ROM 的阈值可以不一样）；开了完全体模式就是 100%。
                opacityHint = desc("")
                addView(opacityHint)
            }
            // 完全体模式：用无障碍覆盖层绕过 Android 12+ 的遮挡判定，透明度才能真到 100%
            trustedSwitch = Switch(this@SettingsActivity).apply {
                text = "完全体模式（透明度可到 100%）"
                textSize = 14f
                setTextColor(colorRes(R.color.dsh_text_secondary))
                setPadding(0, dp(10), 0, dp(2))
                setOnCheckedChangeListener { _, checked -> if (!loading) onTrustedChanged(checked) }
            }
            addView(trustedSwitch)
            trustedHint = desc("")
            addView(trustedHint)

            countVal = valueLabel()
            addView(labelRow("宠物数量", countVal))
            countSeek = slider(2)
            addView(countSeek)
        })

        // ---------------- 互动 ----------------
        root.addView(card {
            addView(sectionTitle("互动"))
            soundSwitch = Switch(this@SettingsActivity).apply {
                text = "点击音效"
                textSize = 14f
                setTextColor(colorRes(R.color.dsh_text_secondary))
                setPadding(0, dp(4), 0, dp(4))
                setOnCheckedChangeListener { _, _ -> if (!loading) onUiChanged() }
            }
            addView(soundSwitch)

            addView(TextView(this@SettingsActivity).apply {
                text = "点击台词库（每行一句）"
                textSize = 12.5f
                setTextColor(colorRes(R.color.dsh_text_hint))
                setPadding(0, dp(10), 0, dp(6))
            })
            sayingsEdit = EditText(this@SettingsActivity).apply {
                hint = "留空则用内置默认台词库"
                setHintTextColor(colorRes(R.color.dsh_text_input_hint))
                setTextColor(colorRes(R.color.dsh_text_input))
                textSize = 14f
                gravity = Gravity.TOP
                minLines = 3
                maxLines = 8
                background = roundRect(colorRes(R.color.dsh_bg_card), 14, colorRes(R.color.dsh_accent_stroke))
                setPadding(dp(12), dp(10), dp(12), dp(10))
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        if (loading) return
                        main.removeCallbacks(saveUiRunnable)
                        main.postDelayed(saveUiRunnable, 600)
                    }
                })
            }
            addView(sayingsEdit)

            // 组合开关：勾选 = 内置默认库 + 自定义一起用；不勾选 = 仅用自定义（留空仍自动回落默认库）
            sayingsCombineSwitch = Switch(this@SettingsActivity).apply {
                text = "台词库含内置默认"
                textSize = 14f
                setTextColor(colorRes(R.color.dsh_text_secondary))
                setPadding(0, dp(8), 0, dp(2))
                isChecked = try {
                    JSONObject(Store.readUiSettings(this@SettingsActivity)).optBoolean("sayingsCombine", false)
                } catch (e: Exception) {
                    false
                }
                setOnCheckedChangeListener { _, checked ->
                    if (loading) return@setOnCheckedChangeListener
                    onUiChanged()
                }
            }
            addView(sayingsCombineSwitch)
        })

        // ---------------- AI ----------------
        root.addView(card {
            addView(sectionTitle("提醒通知"))
            addView(desc("到点提醒的响铃方式；免打扰时段内改为静默通知（只在通知栏留记录，不响不震）。"))
            val quietSw = Switch(this@SettingsActivity).apply {
                text = "启用免打扰时段"
                textSize = 14f
                setTextColor(colorRes(R.color.dsh_text_primary))
            }
            val rangeText = TextView(this@SettingsActivity).apply {
                text = quietRangeText()
                textSize = 13f
                setTextColor(colorRes(R.color.dsh_text_secondary))
                setPadding(0, dp(6), 0, 0)
            }
            val pickRow = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
            val pickBtn = pill("设置时段", false) { showQuietHoursDialog() }
            pickRow.addView(pickBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            quietSummaryView = rangeText
            addView(quietSw)
            addView(rangeText)
            addView(pickRow)
            // 开关只负责保存；时段数值统一在滚轮弹窗里改（此前「开启时顺带弹结束时间」很绕，已移除）
            quietSw.isChecked = Store.prefBool(this@SettingsActivity, "quietEnabled", true)
            quietSw.setOnCheckedChangeListener { _, checked ->
                Store.prefSet(this@SettingsActivity, "quietEnabled", checked)
            }
        })
        root.addView(card {
            addView(sectionTitle("AI 对话"))
            aiEnabled = Switch(this@SettingsActivity).apply {
                text = getString(R.string.settings_ai_enabled)
                textSize = 14f
                setTextColor(colorRes(R.color.dsh_text_secondary))
                setPadding(0, dp(4), 0, dp(6))
                setOnCheckedChangeListener { _, _ -> if (!loading) saveAiSettings(false) }
            }
            addView(aiEnabled)

            addView(desc("OpenAI 兼容接口，填写自动保存。"))

            baseUrlEdit = input("接口地址", "https://api.openai.com/v1")
            addView(baseUrlEdit)
            modelEdit = input("模型名称", "gpt-4o-mini")
            addView(modelEdit)
            apiKeyEdit = input("API Key", "sk-...").apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            addView(apiKeyEdit)

            aiHint = TextView(this@SettingsActivity).apply {
                text = ""
                textSize = 12f
                setTextColor(colorRes(R.color.dsh_success))
                setPadding(0, dp(8), 0, 0)
            }
            addView(aiHint)
        })

        // ---------------- 关于与更新 ----------------
        root.addView(card {
            addView(sectionTitle("关于与更新"))
            addView(
                desc(
                    "${getString(R.string.app_name)} " +
                        "${UpdateChecker.currentVersion(this@SettingsActivity)} · " +
                        "dsh-pet 的 Android 移植（MIT）",
                ),
            )

            val btnRow = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, 0)
            }
            updateBtn = pill("检查更新", true) { checkUpdate(false) }
            releaseBtn = pill("打开发布页", false) { openUrl(UpdateChecker.releasesUrl) }
            btnRow.addView(updateBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            btnRow.addView(
                releaseBtn,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) },
            )
            addView(btnRow)

            updateHint = TextView(this@SettingsActivity).apply {
                text = "检查 GitHub 上是否有新版本。"
                textSize = 12.5f
                setTextColor(colorRes(R.color.dsh_text_hint))
                setPadding(0, dp(8), 0, dp(4))
            }
            addView(updateHint)

            // 开源引用：主次分明地列出「本应用直接基于的」与「第三方依赖」
            val ossBtn = pill("开源许可与引用项目", false) { showOssDialog() }
            addView(
                ossBtn,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) },
            )
        })
        // ---------------- 提醒 tab（与设置同栏，放内容区上方标题栏下方最底部） ----------------
        reminderTabRoot = buildReminderTab()

        // 内容区：两个 ScrollView（设置 / 提醒）叠在 FrameLayout 里，切 tab 只切可见性
        settingsTabScroll = ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundColor(colorRes(R.color.dsh_bg))
            isVerticalScrollBarEnabled = false
        }
        reminderTabScroll = ScrollView(this).apply {
            addView(reminderTabRoot, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundColor(colorRes(R.color.dsh_bg))
            isVerticalScrollBarEnabled = false
        }
        contentFrame = FrameLayout(this).apply {
            addView(settingsTabScroll, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            addView(reminderTabScroll, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }
        reminderTabScroll.visibility = View.GONE

        // 外层：内容区占满 + 底部固定 tab 栏（需求：提醒 与 设置 放在同一栏）
        // 内容区外套 FrameLayout：底部悬浮提示（撤销条）叠在其上，不挤占列表布局
        val contentHost = FrameLayout(this).apply {
            addView(contentFrame, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            snackOverlay = FrameLayout(this@SettingsActivity)
            addView(snackOverlay, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorRes(R.color.dsh_bg))
            addView(contentHost, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            ))
            addView(buildTabBar(), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }

        // 从通知/气泡跳来：带定位 id 则默认进提醒 tab
        reminderFocusId = intent?.getStringExtra(EXTRA_FOCUS_REMINDER_ID)
        if (intent?.getBooleanExtra(EXTRA_REMINDER_TAB, false) == true) {
            switchTab(true)
            refreshReminderList()
            if (!reminderFocusId.isNullOrEmpty()) {
                // 先取局部 val 捕获非空值：lambda 300ms 后执行时 reminderFocusId 已被置 null，
                // 直接读字段会 NPE（崩溃 580 行根因）；if 内已确认非空，!! 安全
                val focusId = reminderFocusId!!
                main.postDelayed({ focusReminderItem(focusId) }, 300L)
                reminderFocusId = null
            }
        } else {
            refreshReminderList()
        }
        return outer
    }

    /** 底部 tab 栏：提醒 | 设置（与设置页卡片同风格——pill 按钮，选中蓝、未选灰） */
    private fun buildTabBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(12))
            setBackgroundColor(colorRes(R.color.dsh_bg_card))
            elevation = dp(6).toFloat()
        }
        tabReminderBtn = tabBarPill("提醒") { switchTab(true) }
        tabSettingsBtn = tabBarPill("设置") { switchTab(false) }
        bar.addView(tabReminderBtn, LinearLayout.LayoutParams(0, dp(44), 1f))
        bar.addView(
            View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(8), 0)
            }
        )
        bar.addView(tabSettingsBtn, LinearLayout.LayoutParams(0, dp(44), 1f))
        paintTabBar()
        return bar
    }

    /** tab 按钮：胶囊，选中=蓝底白字，未选=浅灰底深灰字（与 pill 视觉一致） */
    private fun tabBarPill(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 14f
        isAllCaps = false
        gravity = Gravity.CENTER
        includeFontPadding = false
        stateListAnimator = null
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(14), 0, dp(14), 0)
        setOnClickListener { onClick() }
    }

    /** 切 tab：改可见性 + 刷 tab 栏选中态。toReminder=true 进提醒页。 */
    private fun switchTab(toReminder: Boolean) {
        if (activeTabIsReminder == toReminder) return
        activeTabIsReminder = toReminder
        // contentFrame[0]=设置 scroll，[1]=提醒 scroll，切 tab 只翻可见性
        contentFrame.getChildAt(0)?.visibility = if (toReminder) View.GONE else View.VISIBLE
        contentFrame.getChildAt(1)?.visibility = if (toReminder) View.VISIBLE else View.GONE
        if (toReminder) refreshReminderList()
        paintTabBar()
    }

    private fun paintTabBar() {
        fun paint(b: Button, active: Boolean) {
            b.setTextColor(if (active) Color.WHITE else colorRes(R.color.dsh_section_title))
            b.background = roundRect(
                if (active) colorRes(R.color.dsh_accent) else colorRes(R.color.dsh_accent_soft),
                14, 0,
            )
        }
        paint(tabReminderBtn, activeTabIsReminder)
        paint(tabSettingsBtn, !activeTabIsReminder)
    }

    // ============================================================ 提醒 tab（备忘 / 计划）

    /** 提醒 tab 内容：头部（标题 + 新增按钮）+ 空态 + 列表容器。 */
    private fun buildReminderTab(): LinearLayout {
        val ctx = this@SettingsActivity
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(32))
        }
        // 头部两行：标题独占一行（不再和三个按钮挤在一起），操作行独立、按钮等宽等高
        root.addView(TextView(ctx).apply {
            text = "提醒与计划"
            textSize = 22f
            includeFontPadding = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(colorRes(R.color.dsh_text_primary))
        })
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
            addView(pill("＋ 新增", true) { showAddReminderDialog() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f))
            addView(pill("导入", false) { importReminders() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { leftMargin = dp(8) })
            addView(pill("导出", false) { exportReminders() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { leftMargin = dp(8) })
        })
        // 搜索 + 排序（本地即时过滤，不落盘）
        val searchEt = EditText(ctx).apply {
            hint = "搜索标题 / 步骤 / 标签"
            textSize = 14f
            setSingleLine(true)
            setText(reminderQuery)
            setTextColor(colorRes(R.color.dsh_text_input))
            setHintTextColor(colorRes(R.color.dsh_text_faint))
            background = roundRect(colorRes(R.color.dsh_input_bg), PILL_RADIUS, colorRes(R.color.dsh_accent_stroke))
            setPadding(dp(14), 0, dp(14), 0)
            minHeight = dp(PILL_H)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val q = s?.toString().orEmpty()
                    if (q != reminderQuery) {
                        reminderQuery = q
                        refreshReminderList()
                    }
                }
            })
        }
        val sortChip = pill("排序", false) {
            val opts = listOf("时间最近优先", "时间最晚优先", "按标题")
            val vals = listOf("time_asc", "time_desc", "title")
            showChoiceDialog("排序方式", opts, vals.indexOf(reminderSort).coerceAtLeast(0)) { i ->
                reminderSort = vals[i]
                refreshReminderList()
            }
        }
        val statsChip = pill("统计", false) { showReminderStats() }
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
            addView(searchEt, LinearLayout.LayoutParams(0, dp(PILL_H), 1f))
            addView(sortChip, LinearLayout.LayoutParams(dp(58), dp(PILL_H)).apply { leftMargin = dp(8) })
            addView(statsChip, LinearLayout.LayoutParams(dp(58), dp(PILL_H)).apply { leftMargin = dp(8) })
        })
        // 首次用完左滑后自动隐藏的操作提示（SharedPreferences 记一次性）
        reminderHintBar = TextView(ctx).apply {
            text = "小提示：列表项左滑可「编辑 / 删除」，长按也能编辑"
            textSize = 12.5f
            setTextColor(colorRes(R.color.dsh_text_hint))
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = roundRect(colorRes(R.color.dsh_accent_soft), 12, colorRes(R.color.dsh_accent_stroke))
            visibility = if (Store.prefBool(this@SettingsActivity, KEY_REMINDER_HINT_SHOWN, false))
                View.GONE else View.VISIBLE
        }
        root.addView(reminderHintBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
        root.addView(TextView(ctx).apply {
            text = "到点桌宠旁会弹气泡，点气泡可回来查看 / 勾选。"
            textSize = 12.5f
            setTextColor(colorRes(R.color.dsh_text_hint))
            setPadding(0, dp(10), 0, 0)
        })
        reminderEmpty = TextView(ctx).apply {
            text = "还没有提醒或计划，点上方「＋ 新增」建一个。"
            textSize = 14f
            setTextColor(colorRes(R.color.dsh_text_faint))
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(14), dp(24), 0)
        }
        // 空状态插画：自绘表盘（圆环 + 指针），不使用 emoji，配色跟主题走
        val emptyArt = object : View(ctx) {
            private val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = dp(2).toFloat()
                color = colorRes(R.color.dsh_accent_stroke)
            }
            private val hand = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = dp(3).toFloat()
                strokeCap = android.graphics.Paint.Cap.ROUND
                color = colorRes(R.color.dsh_accent)
            }
            private val dot = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.FILL
                color = colorRes(R.color.dsh_accent_soft)
            }

            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                val w = width.toFloat()
                val h = height.toFloat()
                if (w <= 0f || h <= 0f) return
                val cx = w / 2f
                val cy = h / 2f
                val r = minOf(w, h) / 2f - dp(3)
                canvas.drawCircle(cx, cy, r, ring)
                canvas.drawCircle(cx, cy, r * 0.18f, dot)
                canvas.drawLine(cx, cy, cx, cy - r * 0.58f, hand)
                canvas.drawLine(cx, cy, cx + r * 0.44f, cy, hand)
            }
        }
        reminderEmptyHost = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(36), 0, dp(30))
            addView(emptyArt, LinearLayout.LayoutParams(dp(84), dp(84)))
            addView(reminderEmpty)
        }
        root.addView(reminderEmptyHost)
        reminderListContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(reminderListContainer)
        return root
    }

    /**
     * 刷新提醒列表。改进点：
     *  ·未完成项（含逾期）正常列出，已完成项收进「已完成 N」折叠分组（默认折叠）——列表不被历史占满；
     *  ·逾期项（pending 且已过点）单独标红分组优先展示，视觉上一眼能看出来；
     *  · 构建过程仍是顺序 addView（不引 RecyclerView 依赖），但折叠后实际渲染条目大幅减少，
     *    20+ 条时开销比原来「全量卡片常驻」明显小；
     *  · 倒计时 tick 只挂真正有未完成倒计时的项。
     */
    fun refreshReminderList() {
        countdownLabels.clear()
        countdownTargets.clear()
        main.removeCallbacks(cdTick)
        val all = Reminder.load(Store.readReminders(this))
        val q = reminderQuery.trim().lowercase()
        val list = if (q.isEmpty()) all else all.filter { r ->
            r.title.lowercase().contains(q) ||
                r.tags.any { it.lowercase().contains(q) } ||
                r.steps.any { it.text.lowercase().contains(q) }
        }
        reminderListContainer.removeAllViews()
        reminderEmpty.text = when {
            all.isEmpty() -> "还没有提醒或计划，点上方「＋ 新增」建一个。"
            list.isEmpty() -> "没有匹配「$q」的提醒。"
            else -> ""
        }
        val emptyNow = list.isEmpty()
        reminderEmpty.visibility = if (emptyNow) View.VISIBLE else View.GONE
        reminderEmptyHost?.visibility = if (emptyNow) View.VISIBLE else View.GONE
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val now = System.currentTimeMillis()

        // 三组：逾期（pending 已过点） / 待触发 / 已完成
        val overdue = ArrayList<Reminder>()
        val upcoming = ArrayList<Reminder>()
        val doneList = ArrayList<Reminder>()
        for (r in list) {
            when {
                r.status == Reminder.STATUS_DONE -> doneList.add(r)
                r.timeMs in 1..now && r.status == Reminder.STATUS_PENDING -> overdue.add(r)
                else -> upcoming.add(r)
            }
        }

        // 组内排序（分组语义不变：逾期置顶 / 接下来 / 已完成折叠）
        val cmp: Comparator<Reminder> = when (reminderSort) {
            "time_desc" -> compareByDescending { it.timeMs }
            "title" -> compareBy { it.title.lowercase() }
            else -> compareBy { it.timeMs }
        }
        overdue.sortWith(cmp)
        upcoming.sortWith(cmp)
        doneList.sortWith(cmp)

        fun addItem(r: Reminder) {
            val item = buildReminderItemView(r, fmt)
            reminderListContainer.addView(item, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) })
        }

        // 逾期组（标题 + 条目）
        if (overdue.isNotEmpty()) {
            reminderListContainer.addView(groupHeader("已到点 · ${overdue.size}", colorRes(R.color.dsh_danger)))
            overdue.forEach { addItem(it) }
        }
        // 待触发 / 进行中
        if (upcoming.isNotEmpty()) {
            if (overdue.isNotEmpty()) {
                reminderListContainer.addView(groupHeader("接下来", colorRes(R.color.dsh_section_title)))
            }
            upcoming.forEach { addItem(it) }
        }
        // 已完成（折叠）
        if (doneList.isNotEmpty()) {
            val header = groupHeader(
                "已完成 · ${doneList.size}" + if (doneGroupExpanded) "" else "（点开查看）",
                colorRes(R.color.dsh_text_faint),
            )
            header.setOnClickListener {
                doneGroupExpanded = !doneGroupExpanded
                refreshReminderList()
            }
            reminderListContainer.addView(header)
            if (doneGroupExpanded) doneList.forEach { addItem(it) }
        }

        main.postDelayed({ ReminderScheduler.armAll(this@SettingsActivity) }, 200L)
        // 数据变了→同步刷新桌面小组件（没放置时直接 return，开销可忽略）
        try { ReminderWidgetProvider.refresh(this) } catch (e: Exception) { }
        if (countdownLabels.isNotEmpty()) main.postDelayed(cdTick, 1000L)
    }

    /** 提醒统计弹窗：总量 / 待办 / 逾期 / 已完成 / 本周到期 / 计划步骤完成率。 */
    private fun showReminderStats() {
        val ctx = this@SettingsActivity
        val list = Reminder.load(Store.readReminders(this))
        if (list.isEmpty()) {
            Toast.makeText(ctx, "还没有提醒可统计", Toast.LENGTH_SHORT).show()
            return
        }
        val now = System.currentTimeMillis()
        val weekLater = now + 7L * 24 * 3600 * 1000
        val done = list.count { it.status == Reminder.STATUS_DONE }
        val overdue = list.count {
            it.status != Reminder.STATUS_DONE && it.timeMs in 1..now
        }
        val triggered = list.count {
            it.status == Reminder.STATUS_TRIGGERED && it.timeMs !in 1..now
        }
        val pending = list.size - done - overdue - triggered
        val thisWeek = list.count { it.timeMs in now..weekLater }
        val stepTotal = list.sumOf { it.steps.size }
        val stepDone = list.sumOf { r -> r.steps.count { it.done } }
        val plans = list.count { it is Reminder.Plan }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(colorRes(R.color.dsh_bg_card), 22, 0)
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        card.addView(TextView(ctx).apply {
            text = "提醒统计"
            textSize = 16f
            includeFontPadding = false
            setTextColor(colorRes(R.color.dsh_text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(12))
        })
        val rows = listOf(
            "总数" to "${list.size} 条（计划 $plans 条）",
            "待触发" to "$pending 条",
            "进行中" to "$triggered 条",
            "已到点未处理" to "$overdue 条",
            "已完成" to "$done 条",
            "未来 7 天到期" to "$thisWeek 条",
            "步骤完成度" to if (stepTotal == 0) "—" else "$stepDone / $stepTotal",
        )
        for ((k, v) in rows) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            row.addView(TextView(ctx).apply {
                text = k
                textSize = 14f
                setTextColor(colorRes(R.color.dsh_text_secondary))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(ctx).apply {
                text = v
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(
                    if (k.startsWith("已到点") && overdue > 0) colorRes(R.color.dsh_danger)
                    else colorRes(R.color.dsh_text_primary)
                )
            })
            card.addView(row)
        }
        val dlg = android.app.Dialog(ctx)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dlg.setContentView(card)
        dlg.window?.setLayout(
            (ctx.resources.displayMetrics.widthPixels * 0.8).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dlg.window?.setBackgroundDrawable(ColorDrawable(0x00000000.toInt()))
        dlg.show()
    }

    /** 分组小标题（可点击的分组头也用它）。 */
    private fun groupHeader(text: String, color: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = 12.5f
        setTextColor(color)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        includeFontPadding = false
        setPadding(dp(4), dp(12), dp(4), dp(4))
    }

    /** 用户第一次用左滑/长按后调用：永久隐藏操作提示条。 */
    private fun hideReminderHintOnce() {
        if (Store.prefBool(this, KEY_REMINDER_HINT_SHOWN, false)) return
        Store.prefSet(this, KEY_REMINDER_HINT_SHOWN, true)
        reminderHintBar?.visibility = View.GONE
    }

    /** 导出提醒/计划（三种格式：JSON 备份 / Markdown 清单 / 日历 .ics），均走 SAF、无需存储权限。 */
    private fun exportReminders() {
        val json = Store.readReminders(this)
        if (json.isBlank()) {
            Toast.makeText(this, "还没有提醒可导出", Toast.LENGTH_SHORT).show()
            return
        }
        val list = Reminder.load(json)
        showChoiceDialog(
            "导出格式", listOf("JSON 备份（可再导入）", "Markdown 清单（.md）", "日历文件（.ics）"), 0
        ) { which ->
            when (which) {
                0 -> createExportDoc("dsh-pet-reminders.json", "application/json", null)
                1 -> createExportDoc(
                    "dsh-pet-reminders.md", "text/markdown", buildMarkdownExport(list)
                )
                else -> createExportDoc(
                    "dsh-pet-reminders.ics", "text/calendar", buildIcsExport(list)
                )
            }
        }
    }

    /** 拉起 SAF 新建文档；text 为 null 时表示「用当前 reminders.json 原样导出」（json 备份分支）。 */
    private fun createExportDoc(name: String, mime: String, text: String?) {
        pendingExportText = text ?: ""
        val code = if (text == null) REQ_EXPORT_REMINDERS else REQ_EXPORT_TEXT
        try {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mime
                putExtra(Intent.EXTRA_TITLE, name)
            }
            startActivityForResult(i, code)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开文件选择器：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Markdown 清单：未完成在前（含逾期标记），已完成折叠到文末。 */
    private fun buildMarkdownExport(list: List<Reminder>): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val now = System.currentTimeMillis()
        val sb = StringBuilder()
        sb.append("# 提醒与计划\n\n")
        sb.append("> 导出自 DSH 桌宠 · ").append(fmt.format(java.util.Date(now))).append("\n\n")
        val pending = list.filter { it.status != Reminder.STATUS_DONE }
        val done = list.filter { it.status == Reminder.STATUS_DONE }
        sb.append("## 待办（").append(pending.size).append("）\n\n")
        if (pending.isEmpty()) sb.append("_无_\n\n")
        for (r in pending) {
            val overdue = r.timeMs in 1..now
            sb.append("- [ ] **").append(r.title).append("**")
            if (r.timeMs > 0) sb.append(" — ").append(fmt.format(java.util.Date(r.timeMs)))
            if (overdue) sb.append(" · 已到期")
            if (r.repeat != Reminder.REPEAT_NONE)
                sb.append("（").append(Reminder.repeatLabel(r.repeat)).append("）")
            if (r.advanceMin > 0) sb.append("（提前").append(r.advanceMin).append("分钟）")
            if (r.tags.isNotEmpty())
                sb.append(" ").append(r.tags.joinToString(" ") { "#" + it })
            sb.append("\n")
            for (st in r.steps)
                sb.append("  - [").append(if (st.done) "x" else " ").append("] ")
                    .append(st.text).append("\n")
        }
        sb.append("\n## 已完成（").append(done.size).append("）\n\n")
        if (done.isEmpty()) sb.append("_无_\n")
        for (r in done) {
            sb.append("- [x] ").append(r.title)
            if (r.timeMs > 0) sb.append(" — ").append(fmt.format(java.util.Date(r.timeMs)))
            sb.append("\n")
        }
        return sb.toString()
    }

    /** 日历 .ics（RFC 5545）：每条一个 VEVENT，重复项带 RRULE，提前提醒带 VALARM。 */
    private fun buildIcsExport(list: List<Reminder>): String {
        fun esc(v: String) = v.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
            .replace("\r", "").replace("\n", "\\n")
        val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val sb = StringBuilder()
        sb.append("BEGIN:VCALENDAR\r\n")
        sb.append("VERSION:2.0\r\n")
        sb.append("PRODID:-//DSH Pet//Reminders//CN\r\n")
        sb.append("CALSCALE:GREGORIAN\r\n")
        val stamp = fmt.format(java.util.Date())
        for (r in list) {
            if (r.timeMs <= 0) continue
            sb.append("BEGIN:VEVENT\r\n")
            sb.append("UID:").append(r.id).append("@dsh-pet\r\n")
            sb.append("DTSTAMP:").append(stamp).append("\r\n")
            sb.append("DTSTART:").append(fmt.format(java.util.Date(r.timeMs))).append("\r\n")
            sb.append("DURATION:PT15M\r\n")
            sb.append("SUMMARY:").append(esc(r.title)).append("\r\n")
            var desc = "状态：" + when (r.status) {
                Reminder.STATUS_DONE -> "已完成"
                Reminder.STATUS_TRIGGERED -> "进行中"
                else -> "待触发"
            }
            if (r.steps.isNotEmpty()) {
                desc += "\n步骤：" + r.steps.joinToString("; ") {
                    (if (it.done) "[x] " else "[ ] ") + it.text
                }
            }
            if (r.tags.isNotEmpty()) desc += "\n标签：" + r.tags.joinToString(" ")
            sb.append("DESCRIPTION:").append(esc(desc)).append("\r\n")
            when (r.repeat) {
                Reminder.REPEAT_DAILY -> sb.append("RRULE:FREQ=DAILY\r\n")
                Reminder.REPEAT_WEEKLY -> sb.append("RRULE:FREQ=WEEKLY\r\n")
                Reminder.REPEAT_WEEKDAYS ->
                    sb.append("RRULE:FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR\r\n")
                Reminder.REPEAT_MONTHLY -> sb.append("RRULE:FREQ=MONTHLY\r\n")
            }
            if (r.advanceMin > 0) {
                sb.append("BEGIN:VALARM\r\n")
                sb.append("TRIGGER:-PT").append(r.advanceMin).append("M\r\n")
                sb.append("ACTION:DISPLAY\r\n")
                sb.append("DESCRIPTION:").append(esc(r.title)).append("\r\n")
                sb.append("END:VALARM\r\n")
            }
            sb.append("END:VEVENT\r\n")
        }
        sb.append("END:VCALENDAR\r\n")
        return sb.toString()
    }

    /** 从用户选的文件导入提醒（合并去重：同 id 保留本地；新 id 追加）。 */
    private fun importReminders() {
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(i, REQ_IMPORT_REMINDERS)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开文件选择器：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** 删除并给出 4 秒「撤销」窗口（撤销恢复整表快照，含原状态与步骤）。
     *  提示条以「底部悬浮」形式叠在内容区底部，使用专门的 snack 色（暗黑模式自动切换），
     *  不再往列表里插一行，也不占用列表布局。 */
    private fun deleteWithUndo(r: Reminder) {
        val before = Store.readReminders(this)
        ReminderScheduler.remove(this, r.id)
        refreshReminderList()
        undoSnapshot = before
        undoTimer?.let { main.removeCallbacks(it) }

        val host = snackOverlay ?: return
        snackBar?.let { host.removeView(it) }
        // 提示条本身不消费整屏触摸（clickable=false），只有「撤销」按钮可点
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundRect(colorRes(R.color.dsh_snack_bg), 16, 0)
            elevation = dp(8).toFloat()
            setPadding(dp(16), dp(12), dp(10), dp(12))
            isClickable = false
        }
        bar.addView(TextView(this).apply {
            text = "已删除「${r.title}」"
            textSize = 13.5f
            setTextColor(colorRes(R.color.dsh_snack_fg))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(TextView(this).apply {
            text = "撤销"
            textSize = 13.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(colorRes(R.color.dsh_snack_action))
            setPadding(dp(14), dp(6), dp(10), dp(6))
            setOnClickListener {
                val snap = undoSnapshot
                if (!snap.isNullOrEmpty()) {
                    Store.writeReminders(this@SettingsActivity, snap)
                    ReminderScheduler.armAll(this@SettingsActivity)
                    refreshReminderList()
                    Toast.makeText(this@SettingsActivity, "已撤销删除", Toast.LENGTH_SHORT).show()
                }
                undoSnapshot = null
                dismissSnackBar()
            }
        })
        val lp = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ).apply {
            leftMargin = dp(16)
            rightMargin = dp(16)
            bottomMargin = dp(16)
        }
        host.addView(bar, lp)
        snackBar = bar
        // 入场：淡入 + 上滑 12dp
        bar.alpha = 0f
        bar.translationY = dp(12).toFloat()
        bar.animate().alpha(1f).translationY(0f).setDuration(180L).start()

        val hide = object : Runnable {
            override fun run() {
                dismissSnackBar()
                undoSnapshot = null
            }
        }
        undoTimer = hide
        main.postDelayed(hide, 4000L)
    }

    /** 收起底部悬浮提示条（带退场动画，结束后从宿主移除）。 */
    private fun dismissSnackBar() {
        val b = snackBar ?: return
        snackBar = null
        b.animate().alpha(0f).translationY(dp(12).toFloat()).setDuration(160L)
            .withEndAction { (b.parent as? android.view.ViewGroup)?.removeView(b) }
            .start()
    }


    /** 单条提醒卡片：标题 + 时间 + 状态徽章 + 步骤（plan）+ 删除按钮。 */
    /** 单条提醒卡片：紧凑 + 视觉对齐。
     *  · 顶行：标题（左，省略号）+ 状态徽章（右）
     *  · 信息行：时间（左）+ 步骤进度（右，plan 才显示），基线对齐
     *  · 步骤：统一缩进（与标题左缘对齐），当前进行中步骤右侧标记「进行中」
     *  · 底行：删除按钮靠右、小号、危险色背景
     */
    /** 免打扰时段的展示文案。 */
    private fun quietRangeText(): String {
        val s = Store.prefInt(this, "quietStart", 23)
        val e = Store.prefInt(this, "quietEnd", 7)
        return String.format("免打扰时段：%02d:00 - %02d:00", s, e)
    }

    /** 免打扰时段设置：两个滚轮（开始 / 结束）并排，松手吸附、跨格有咔哒声，一次设完。 */
    private fun showQuietHoursDialog() {
        val ctx = this@SettingsActivity
        // 顶部预览：滚轮一动就更新，确认前就能看清最终时段
        val preview = TextView(ctx).apply {
            text = String.format(
                "%02d:00 至 %02d:00，此期间不响铃不震动",
                Store.prefInt(ctx, "quietStart", 23), Store.prefInt(ctx, "quietEnd", 7),
            )
            textSize = 12.5f
            setTextColor(colorRes(R.color.dsh_text_hint))
            setPadding(0, 0, 0, dp(14))
        }
        fun makePicker(init: Int) = android.widget.NumberPicker(ctx).apply {
            minValue = 0
            maxValue = 23
            wrapSelectorWheel = true
            value = init.coerceIn(0, 23)
            setFormatter { v -> String.format("%02d:00", v) }
            descendantFocusability = android.widget.NumberPicker.FOCUS_BLOCK_DESCENDANTS
            // 暗色模式：NumberPicker 内部文字是默认黑，需要逐个子 View 刷成主题色
            post {
                val color = colorRes(R.color.dsh_text_primary)
                for (i in 0 until childCount) {
                    val c = getChildAt(i)
                    if (c is TextView) c.setTextColor(color)
                }
            }
        }
        val startPicker = makePicker(Store.prefInt(ctx, "quietStart", 23))
        val endPicker = makePicker(Store.prefInt(ctx, "quietEnd", 7))
        val startLabel = TextView(ctx).apply {
            text = "开始"
            textSize = 12f
            setTextColor(colorRes(R.color.dsh_text_faint))
            gravity = Gravity.CENTER
        }
        val endLabel = TextView(ctx).apply {
            text = "结束"
            textSize = 12f
            setTextColor(colorRes(R.color.dsh_text_faint))
            gravity = Gravity.CENTER
        }
        fun syncPreview() {
            preview.text = String.format(
                "%02d:00 至 %02d:00，此期间不响铃不震动",
                startPicker.value, endPicker.value,
            )
        }
        startPicker.setOnValueChangedListener { _, _, _ -> syncPreview() }
        endPicker.setOnValueChangedListener { _, _, _ -> syncPreview() }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(colorRes(R.color.dsh_bg_card), 24, 0)
            setPadding(dp(16), dp(16), dp(16), dp(14))
        }
        card.addView(TextView(ctx).apply {
            text = "免打扰时段"
            textSize = 17f
            includeFontPadding = false
            setTextColor(colorRes(R.color.dsh_text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(4), 0, dp(4), dp(4))
        })
        card.addView(preview)
        // 两列滚轮：各占一半宽度，中间「至」分隔，视觉居中对称
        card.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundRect(colorRes(R.color.dsh_input_bg), 16, colorRes(R.color.dsh_accent_stroke))
            setPadding(dp(6), dp(8), dp(6), dp(8))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(startLabel, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(startPicker, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(150)))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(ctx).apply {
                text = "至"
                textSize = 13f
                setTextColor(colorRes(R.color.dsh_text_hint))
                setPadding(dp(10), 0, dp(10), 0)
            })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(endLabel, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(endPicker, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(150)))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(TextView(ctx).apply {
            text = "支持跨天，例如 23:00 - 07:00"
            textSize = 12f
            setTextColor(colorRes(R.color.dsh_text_faint))
            setPadding(dp(4), dp(10), dp(4), 0)
        })
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, 0)
        }
        val cancelBtn = pill("取消", false) { }
        val saveBtn = pill("保存", true) { }
        btnRow.addView(cancelBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btnRow.addView(saveBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { leftMargin = dp(12) })
        card.addView(btnRow)

        val dlg = android.app.Dialog(ctx)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dlg.setContentView(card)
        dlg.window?.setLayout(
            (ctx.resources.displayMetrics.widthPixels * 0.9).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        dlg.window?.setBackgroundDrawable(ColorDrawable(0x00000000.toInt()))
        cancelBtn.setOnClickListener { dlg.dismiss() }
        saveBtn.setOnClickListener {
            Store.prefSetInt(ctx, "quietStart", startPicker.value)
            Store.prefSetInt(ctx, "quietEnd", endPicker.value)
            // 改完时段立刻回写界面文本：重新加载设置页可能打断滚动，这里只更新摘要
            quietSummaryView?.text = quietRangeText()
            Toast.makeText(ctx, quietRangeText(), Toast.LENGTH_SHORT).show()
            dlg.dismiss()
        }
        dlg.show()
    }

    /** 通用单选弹窗：卡片风格、选项竖排、点选即回调并关闭（用于重复规则 / 提前提醒时长等）。 */
    private fun showChoiceDialog(
        title: String,
        labels: List<String>,
        selected: Int,
        onPick: (Int) -> Unit,
    ) {
        val ctx = this@SettingsActivity
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(colorRes(R.color.dsh_bg_card), 22, 0)
            setPadding(dp(16), dp(16), dp(16), dp(14))
        }
        card.addView(TextView(ctx).apply {
            text = title
            textSize = 16f
            includeFontPadding = false
            setTextColor(colorRes(R.color.dsh_text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(2), 0, 0, dp(12))
        })
        val dlg = android.app.Dialog(ctx)
        labels.forEachIndexed { i, label ->
            card.addView(TextView(ctx).apply {
                text = label
                textSize = 14.5f
                setTextColor(
                    if (i == selected) colorRes(R.color.dsh_accent)
                    else colorRes(R.color.dsh_text_primary)
                )
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = false
                minHeight = dp(PILL_H)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = roundRect(
                    colorRes(R.color.dsh_input_bg), PILL_RADIUS,
                    if (i == selected) colorRes(R.color.dsh_accent) else 0,
                )
                setOnClickListener {
                    onPick(i)
                    dlg.dismiss()
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) })
        }
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dlg.setContentView(card)
        dlg.window?.setLayout(
            (ctx.resources.displayMetrics.widthPixels * 0.72).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dlg.window?.setBackgroundDrawable(ColorDrawable(0x00000000.toInt()))
        dlg.show()
    }

    private fun buildReminderItemView(r: Reminder, fmt: SimpleDateFormat): View {
        val ctx = this@SettingsActivity
        val card = card { }
        card.removeAllViews()
        // 紧凑化：提醒列表项专用小内边距 + 小项间距（memo 单条高度约 90dp → 60dp）
        card.setPadding(dp(12), dp(8), dp(12), dp(8))
        (card.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8)
        val isDone = r.status == Reminder.STATUS_DONE
        val isOverdue = !isDone && r.timeMs > 0 && r.timeMs <= System.currentTimeMillis()
        // 逾期项：整卡左侧红色色条 + 淡红描边，扫一眼就能看出哪些过期了
        if (isOverdue) {
            val g = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(colorRes(R.color.dsh_bg_card))
                setStroke(dp(1), colorRes(R.color.dsh_danger))
            }
            card.background = g
        }
        val plan = r as? Reminder.Plan
        val doneCount = plan?.steps?.count { it.done } ?: 0
        val nextIdx = plan?.steps?.indexOfFirst { !it.done } ?: -1

        // 顶行：标题（左，可省略） + 徽章 + 删除小按钮（右，不再独占一行）
        val headRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headRow.addView(TextView(ctx).apply {
            text = r.title
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(if (isDone) colorRes(R.color.dsh_text_faint) else colorRes(R.color.dsh_text_primary))
            paintFlags = if (isDone) Paint.STRIKE_THRU_TEXT_FLAG else 0
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        headRow.addView(statusBadge(r), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        card.addView(headRow)
        //（删除按钮改为「左滑露出」，顶行不再放删除）

        // 信息行：时间（左） + 步骤进度（右，仅 plan）—— 同一行两端对齐
        val timeLabel = when {
            r.timeMs <= 0 -> "未设时间"
            isDone -> "已完成 · ${fmt.format(java.util.Date(r.timeMs))}"
            else -> "${fmt.format(java.util.Date(r.timeMs))}"
        }
        val metaRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        metaRow.addView(TextView(ctx).apply {
            text = timeLabel
            textSize = 12.5f
            setTextColor(colorRes(R.color.dsh_text_hint))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // 重复 / 提前提醒标记（只有设置了才显示，避免占用视觉噪音）
        val repeatTag = buildString {
            if (r.repeat != Reminder.REPEAT_NONE) append(Reminder.repeatLabel(r.repeat))
            if (r.advanceMin > 0) {
                if (isNotEmpty()) append(" · ")
                append("提前${r.advanceMin}分")
            }
        }
        if (r.tags.isNotEmpty()) {
            metaRow.addView(TextView(ctx).apply {
                text = r.tags.joinToString(" · ") { "#$it" }
                textSize = 11f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(colorRes(R.color.dsh_text_secondary))
                setPadding(dp(6), dp(1), dp(6), dp(1))
                background = roundRect(colorRes(R.color.dsh_input_bg), 8, 0)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(8) })
        }
        if (repeatTag.isNotEmpty()) {
            metaRow.addView(TextView(ctx).apply {
                text = repeatTag
                textSize = 11f
                setTextColor(colorRes(R.color.dsh_accent))
                setPadding(dp(6), dp(1), dp(6), dp(1))
                background = roundRect(colorRes(R.color.dsh_accent_soft), 8, 0)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(8) })
        }
        // 自适应倒计时（cdTick 每秒刷新：<12h 精确到秒；未到点变强调色）
        if (r.timeMs > 0 && !isDone) {
            val cd = TextView(ctx).apply {
                text = countdownText(r.timeMs)
                textSize = 12.5f
                setTextColor(colorRes(R.color.dsh_text_hint))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            metaRow.addView(cd, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10) })
            countdownLabels.add(cd)
            countdownTargets.add(r.timeMs)
        }
        if (plan != null && plan.steps.isNotEmpty()) {
            metaRow.addView(TextView(ctx).apply {
                text = "步骤 $doneCount/${plan.steps.size}"
                textSize = 12f
                setTextColor(if (plan.allDone) colorRes(R.color.dsh_success) else colorRes(R.color.dsh_accent))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10) })
            // 一键全部完成（步骤多时免去逐条勾选；二次确认避免误触）
            if (!plan.allDone && !isDone) {
                metaRow.addView(TextView(ctx).apply {
                    text = "全部完成"
                    textSize = 11.5f
                    setTextColor(colorRes(R.color.dsh_success))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(dp(6), dp(1), dp(6), dp(1))
                    background = roundRect(colorRes(R.color.dsh_success_bg), 8, 0)
                    setOnClickListener {
                        android.app.AlertDialog.Builder(ctx)
                            .setMessage("把这 ${plan.steps.size} 个步骤全部勾完？")
                            .setPositiveButton("全部完成") { _, _ ->
                                ReminderScheduler.markAllStepsDone(ctx, r.id)
                                refreshReminderList()
                                Toast.makeText(ctx, "已全部完成", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
            }
        }
        card.addView(metaRow)

        // 步骤列表：统一缩进，与标题左缘对齐
        if (plan != null && plan.steps.isNotEmpty()) {
            val stepHost = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(3), 0, 0)
            }
            for ((i, st) in plan.steps.withIndex()) {
                val enabled = st.done || i == nextIdx
                val srow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(3), 0, 0)
                }
                val cb = CheckBox(ctx).apply {
                    text = st.text
                    textSize = 13.5f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    isChecked = st.done
                    isEnabled = enabled
                    setTextColor(if (st.done) colorRes(R.color.dsh_text_faint) else colorRes(R.color.dsh_text_secondary))
                    setOnCheckedChangeListener { _, isChecked ->
                        if (loading) return@setOnCheckedChangeListener
                        if (!enabled) return@setOnCheckedChangeListener
                        val newChecked = if (st.done) !isChecked else isChecked
                        if (newChecked == st.done) return@setOnCheckedChangeListener
                        ReminderScheduler.toggleStep(this@SettingsActivity, r.id, i)
                        refreshReminderList()
                    }
                }
                srow.addView(cb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (i == nextIdx && !st.done) {
                    srow.addView(TextView(ctx).apply {
                        text = "进行中"
                        textSize = 11f
                        setTextColor(colorRes(R.color.dsh_accent))
                        setPadding(0, 0, dp(4), 0)
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                    })
                }
                stepHost.addView(srow)
            }
            card.addView(stepHost)
        }

        // ===== 左滑露出「编辑 + 删除」（微信式）+ 长按弹编辑弹窗 =====
        // 用自定义 ViewGroup（SwipeRowLayout）接管手势：DOWN 不拦截让子元素点击正常，
        // 判定水平拖动才 intercept；长按与其解耦（任何位移立即取消）——修复「滑不动/误弹编辑窗」。
        val row = SwipeRowLayout(ctx)
        // 操作层：编辑 + 删除 两个按钮（显式入口，避免用户不知道长按可编辑）；平时 GONE，左滑才滑入
        val editBtn = Button(ctx).apply {
            text = "编辑"
            textSize = 12f
            isAllCaps = false
            stateListAnimator = null
            setTextColor(Color.WHITE)
            background = roundRect(colorRes(R.color.dsh_accent), 10, 0)
            setOnClickListener {
                row.closeSwipe()
                showEditReminderDialog(r)
            }
        }
        val delBtn = Button(ctx).apply {
            text = "删除"
            textSize = 12f
            isAllCaps = false
            stateListAnimator = null
            setTextColor(Color.WHITE)
            background = roundRect(colorRes(R.color.dsh_danger), 10, 0)
            setOnClickListener {
                row.closeSwipe()
                confirmDeleteReminder(r)
            }
        }
        val actionBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(editBtn, LinearLayout.LayoutParams(
                dp(60), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(delBtn, LinearLayout.LayoutParams(
                dp(60), ViewGroup.LayoutParams.MATCH_PARENT).apply { leftMargin = dp(5) })
        }
        (card.layoutParams as? ViewGroup.MarginLayoutParams)?.let { it.topMargin = 0 }
        row.bind(card, actionBar, dp(125))
        row.onLongPress = { hideReminderHintOnce(); showEditReminderDialog(r) }
        row.onInteract = { hideReminderHintOnce() }
        return row
    }

    /** 长按提醒项 → 编辑弹窗：改标题/时间 + 提醒方式开关（桌宠气泡 / 通知栏）+ 计划步骤二次编辑。 */
    private fun showEditReminderDialog(r: Reminder) {
        val ctx = this@SettingsActivity
        val tf = android.graphics.Typeface.DEFAULT
        val isPlan = r is Reminder.Plan
        val card2 = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(colorRes(R.color.dsh_bg_card), 24, 0)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val titleBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(12), dp(6))
        }
        titleBar.addView(TextView(ctx).apply {
            text = "编辑提醒"
            textSize = 17f
            setTextColor(colorRes(R.color.dsh_text_primary))
            setTypeface(tf, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val closeX = TextView(ctx).apply {
            text = "✕"; textSize = 16f; gravity = Gravity.CENTER
            setTextColor(colorRes(R.color.dsh_text_hint)); setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        titleBar.addView(closeX)
        card2.addView(titleBar)
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(12))
        }
        // 标题
        body.addView(desc("标题"))
        val titleEt = EditText(ctx).apply {
            hint = "提醒标题"; textSize = 14f; setSingleLine(true)
            setTextColor(colorRes(R.color.dsh_text_input))
            background = roundRect(colorRes(R.color.dsh_input_bg), 12, colorRes(R.color.dsh_accent_stroke))
            setPadding(dp(12), dp(8), dp(12), dp(8)); setText(r.title)
        }
        body.addView(titleEt)
        val tagsEt = EditText(ctx).apply {
            hint = "标签（逗号分隔，可空）"; textSize = 14f; setSingleLine(true)
            setTextColor(colorRes(R.color.dsh_text_input))
            background = roundRect(colorRes(R.color.dsh_input_bg), 12, colorRes(R.color.dsh_accent_stroke))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setText(r.tags.joinToString(","))
        }
        body.addView(tagsEt)
        // 时间
        body.addView(desc("时间 / 日期"))
        val wheel = TimeWheelView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220))
            val ldt = java.time.Instant.ofEpochMilli(r.timeMs)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
            setPicked(ldt.year, ldt.monthValue, ldt.dayOfMonth, ldt.hour, ldt.minute, ldt.second)
        }
        body.addView(wheel)
        // 重复规则 / 提前提醒（同新建弹窗：两个 chip + 单选弹窗）
        val repeatVals = listOf(
            Reminder.REPEAT_NONE, Reminder.REPEAT_DAILY, Reminder.REPEAT_WEEKLY,
            Reminder.REPEAT_WEEKDAYS, Reminder.REPEAT_MONTHLY,
        )
        val repeatTexts = repeatVals.map { Reminder.repeatLabel(it) }
        val advVals = listOf(0, 5, 15, 30, 60)
        val advTexts = listOf("准时", "提前 5 分钟", "提前 15 分钟", "提前 30 分钟", "提前 1 小时")
        var selRepeat = repeatVals.firstOrNull { it == r.repeat } ?: Reminder.REPEAT_NONE
        var selAdvMin = if (advVals.contains(r.advanceMin)) r.advanceMin else 0
        var repeatChip: Button? = null
        var advChip: Button? = null
        repeatChip = pill("重复：${Reminder.repeatLabel(selRepeat)}", false) {
            showChoiceDialog("重复", repeatTexts, repeatVals.indexOf(selRepeat)) { i ->
                selRepeat = repeatVals[i]
                repeatChip?.text = "重复：${repeatTexts[i]}"
            }
        }
        advChip = pill("提前：${advTexts[advVals.indexOf(selAdvMin)]}", false) {
            showChoiceDialog("提前提醒", advTexts, advVals.indexOf(selAdvMin)) { i ->
                selAdvMin = advVals[i]
                advChip?.text = "提前：${advTexts[i]}"
            }
        }
        body.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(6))
            addView(repeatChip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(advChip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(8) })
        })
        body.addView(desc("提醒方式"))
        val bubbleSw = Switch(ctx).apply {
            text = "在桌宠旁弹气泡"; setTextColor(colorRes(R.color.dsh_text_primary)); textSize = 13.5f
            isChecked = Reminder.showBubble(r)
        }
        val notifSw = Switch(ctx).apply {
            text = "在通知栏显示"; setTextColor(colorRes(R.color.dsh_text_primary)); textSize = 13.5f
            setPadding(0, dp(6), 0, 0); isChecked = Reminder.showNotification(r)
        }
        body.addView(bubbleSw); body.addView(notifSw)
        // 计划步骤二次编辑（仅 plan）
        val stepEdits = ArrayList<EditText>()
        if (isPlan) {
            val plan = r as Reminder.Plan
            body.addView(desc("步骤（可改文本 / 删行）"))
            for (s in plan.steps) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                }
                val et = EditText(ctx).apply {
                    hint = "步骤内容"; textSize = 14f; setSingleLine(true); setText(s.text)
                    setTextColor(colorRes(R.color.dsh_text_input))
                    background = roundRect(colorRes(R.color.dsh_input_bg), 12, colorRes(R.color.dsh_accent_stroke))
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                }
                row.addView(et, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val del = TextView(ctx).apply {
                    text = "✕"; textSize = 17f; setTextColor(colorRes(R.color.dsh_danger)); setPadding(dp(10), 0, dp(2), 0)
                }
                del.setOnClickListener { body.removeView(row); stepEdits.remove(et) }
                row.addView(del)
                body.addView(row, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
                stepEdits.add(et)
            }
        }
        // 把正文挂进卡片（此前漏挂导致弹窗只剩两个按钮！）——套一层 ScrollView 兜底，
        // 步骤多时弹窗内部滚，高度封顶 85% 屏高
        val bodyScroll = android.widget.ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = true
            addView(body, android.view.ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        card2.addView(bodyScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // 保存
        val saveRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        val cancelBtn = pill("取消", false) { }
        val saveBtn = pill("保存", true) { }
        saveRow.addView(cancelBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        saveRow.addView(saveBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
        card2.addView(saveRow)
        val dlg = android.app.Dialog(ctx)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dlg.setContentView(card2, LinearLayout.LayoutParams(
            (ctx.resources.displayMetrics.widthPixels * 0.9).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
        dlg.window?.setLayout(
            (ctx.resources.displayMetrics.widthPixels * 0.9).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        dlg.window?.setBackgroundDrawable(ColorDrawable(0x00000000.toInt()))
        // 内容超高才封顶（动态步骤编辑）
        val maxH = (ctx.resources.displayMetrics.heightPixels * 0.85).toInt()
        closeX.setOnClickListener { dlg.dismiss() }
        cancelBtn.setOnClickListener { dlg.dismiss() }
        saveBtn.setOnClickListener {
            val newTitle = titleEt.text.toString().trim()
            if (newTitle.isEmpty()) {
                Toast.makeText(ctx, "标题不能为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val newTime = wheel.picked().toEpochMilli()
            val sb = bubbleSw.isChecked
            val sn = notifSw.isChecked
            ReminderScheduler.update(ctx, r.id) { cur ->
                when (cur) {
                    is Reminder.Memo -> cur.copy(title = newTitle, timeMs = newTime,
                        showBubble = sb, showNotification = sn,
                        repeat = selRepeat, advanceMin = selAdvMin,
                        tags = Reminder.parseTags(tagsEt.text.toString()))
                    is Reminder.Plan -> {
                        val newSteps = stepEdits.map { it.text.toString().trim() }
                            .filter { it.isNotEmpty() }.map { Reminder.Step(it, cur.steps.firstOrNull()?.done ?: false) }
                        cur.copy(title = newTitle, timeMs = newTime,
                            showBubble = sb, showNotification = sn,
                            repeat = selRepeat, advanceMin = selAdvMin,
                            tags = Reminder.parseTags(tagsEt.text.toString()),
                            steps = newSteps, status = if (cur.status == Reminder.STATUS_DONE && newSteps.isNotEmpty()) Reminder.STATUS_TRIGGERED else cur.status)
                    }
                }
            }
            refreshReminderList()
            Toast.makeText(ctx, "已保存修改", Toast.LENGTH_SHORT).show()
            dlg.dismiss()
        }
        dlg.show()
        // 封顶时才切 bodyScroll 到「0 高 + weight」内部滚动；内容矮（备忘）保持 WRAP 贴合
        card2.post {
            if (card2.height > maxH) {
                (card2.layoutParams as? LinearLayout.LayoutParams)?.height = maxH
                (bodyScroll.layoutParams as? LinearLayout.LayoutParams)?.let {
                    it.height = 0
                    it.weight = 1f
                }
                card2.requestLayout()
            }
        }
    }

    /** 状态徽章：待触发 / 进行中 / 圆角小胶囊。 */
    /** 自适应倒计时文案：≥30天→「N个月后」，≥48小时→「N天后」，≥12小时→「N小时后」，
     *  <12小时→具体「X小时Y分Z秒」（不足1小时则「Y分Z秒」）；已到点/已过点→「已到点」。 */
    private fun countdownText(timeMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val delta = timeMs - nowMs
        if (delta <= 0) return "已到点"
        val days = delta / (24L * 3600 * 1000)
        val hours = delta / (3600L * 1000)
        return when {
            days >= 30 -> "${(days + 29) / 30}个月后"
            hours >= 48 -> "${days}天后"
            hours >= 12 -> "${hours}小时后"
            else -> {
                val h = hours
                val m = (delta % 3600000L) / 60000L
                val sec = (delta % 60000L) / 1000L
                if (h > 0) "${h}小时${m}分${sec}秒" else "${m}分${sec}秒"
            }
        }
    }

    private fun statusBadge(r: Reminder): View = TextView(this@SettingsActivity).apply {
        val t: Triple<String, Int, Int> = when (r.status) {
            Reminder.STATUS_DONE -> Triple("已完成", colorRes(R.color.dsh_success), colorRes(R.color.dsh_success_bg))
            Reminder.STATUS_TRIGGERED -> {
                val lbl = (r as? Reminder.Plan)?.let { if (it.allDone) "已完成" else "进行中" } ?: "进行中"
                Triple(lbl, colorRes(R.color.dsh_warning), colorRes(R.color.dsh_warning_bg))
            }
            else -> Triple("待触发", colorRes(R.color.dsh_section_title), colorRes(R.color.dsh_accent_soft))
        }
        val (label, color, bg) = t
        text = label
        textSize = 11f
        setTextColor(color)
        background = roundRect(bg, 99, 0)
        setPadding(dp(8), dp(3), dp(8), dp(3))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    /** 删除前二次确认（需求：删除计划需二次确认）。 */
    private fun confirmDeleteReminder(r: Reminder) {
        val ctx = this@SettingsActivity
        val tf = android.graphics.Typeface.DEFAULT
        // ---- 自定义卡片 Dialog：与「新建提醒」统一 UI（圆角卡片 + 色板色 + 自绘按钮栏）----
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(colorRes(R.color.dsh_bg_card), 24, 0)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        // 标题栏
        val titleBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(12), dp(6))
        }
        titleBar.addView(TextView(ctx).apply {
            text = "删除提醒"
            textSize = 17f
            setTextColor(colorRes(R.color.dsh_text_primary))
            setTypeface(tf, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val closeX = TextView(ctx).apply {
            text = "✕"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(colorRes(R.color.dsh_text_hint))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        titleBar.addView(closeX)
        card.addView(titleBar)
        // 内容区
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(10))
        }
        body.addView(TextView(ctx).apply {
            text = "确定删除「${r.title}」？"
            textSize = 16f
            setTextColor(colorRes(R.color.dsh_text_primary))
            setTypeface(tf, android.graphics.Typeface.BOLD)
        })
        body.addView(TextView(ctx).apply {
            text = "删除后无法恢复。"
            textSize = 13.5f
            setTextColor(colorRes(R.color.dsh_text_hint))
            setPadding(0, dp(6), 0, 0)
        })
        card.addView(body)
        // 按钮栏：取消 / 删除（危险色主按钮）
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(10), dp(16), dp(16))
        }
        val cancelBtn = pill("取消", false) { }
        val deleteBtn = Button(ctx).apply {
            text = "删除"
            textSize = 13.5f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = roundRect(colorRes(R.color.dsh_danger), 14, 0)
            stateListAnimator = null
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(6), dp(10), dp(6), dp(10))
        }
        btnRow.addView(cancelBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btnRow.addView(deleteBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
        card.addView(btnRow)
        // 组装 Dialog
        val dlg = android.app.Dialog(ctx)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dlg.setContentView(card, LinearLayout.LayoutParams(
            (ctx.resources.displayMetrics.widthPixels * 0.9).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        dlg.window?.setLayout(
            (ctx.resources.displayMetrics.widthPixels * 0.9).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dlg.window?.setBackgroundDrawable(ColorDrawable(0x00000000.toInt()))
        closeX.setOnClickListener { dlg.dismiss() }
        cancelBtn.setOnClickListener { dlg.dismiss() }
        deleteBtn.setOnClickListener {
            dlg.dismiss()
            deleteWithUndo(r)
        }
        dlg.show()
    }

    /** 定位并高亮某条提醒（气泡/通知点击跳来用）：滚动到该项 + 蓝框闪一下。
     *  列表已按 timeMs 升序，focus 的 id 在列表中的序号 = indexOfFirst { it.id == id }。 */
    private fun focusReminderItem(id: String) {
        val list = Reminder.load(Store.readReminders(this))
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0 || idx >= reminderListContainer.childCount) return
        val tv = reminderListContainer.getChildAt(idx)
        reminderTabScroll.smoothScrollTo(0, (tv.top - dp(16)).coerceAtLeast(0))
        val orig = tv.background
        tv.background = roundRect(colorRes(R.color.dsh_focus_highlight), 20, colorRes(R.color.dsh_accent))
        main.postDelayed({ tv.background = orig }, 1600L)
    }
/**
 * 新增提醒/计划对话框（自定义 Dialog，统一 UI 风格：卡片圆角 + 色板色 + 自绘标题栏/按钮栏，
 * 替代原生 AlertDialog 的默认外观）。步骤区用固定高度宿主，备忘/计划两种模式尺寸一致。
 * 漏填时保持弹窗不关闭（不丢已填内容）。
 */
private fun showAddReminderDialog() {
    val ctx = this@SettingsActivity
    val isPlan = java.util.concurrent.atomic.AtomicBoolean(false)
    fun paintTypeBtn(b: Button, active: Boolean) {
        b.setTextColor(if (active) Color.WHITE else colorRes(R.color.dsh_text_secondary))
        b.background = roundRect(
            if (active) colorRes(R.color.dsh_accent) else colorRes(R.color.dsh_accent_soft),
            14, if (active) 0 else colorRes(R.color.dsh_accent_stroke))
    }
    val memoBtn = pill("备忘", true) {}
    val planBtn = pill("计划（多步骤）", false) {}
    val typeRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, 0, 0, dp(12))
        addView(memoBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(planBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { leftMargin = dp(8) })
    }
    val titleEdit = input("标题", "比如：喝水 / 交房租")
    val tagsEdit = input("标签", "用逗号分隔，如：工作,生活（可空）")
    val timeWheel = TimeWheelView(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(220),
        )
    }
    val wheelHost = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(6), 0, dp(4))
        addView(desc("年 / 月 / 日 / 时 / 分 / 秒（滚动选择，松手自动吸附）"))
        addView(timeWheel)
    }
    // 步骤区：固定高度宿主，备忘显占位、计划显输入框+加号（两模式尺寸一致）
    val stepHost = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
    }
    val stepsContainer = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
    }
    // 步骤列表 = 弹窗内「唯一」的滚动区（固定可滚高度 maxStepH，超了就内部滚，不整页滚）
    val maxStepH = dp(150)
    val stepsScroll = android.widget.ScrollView(ctx).apply {
        isVerticalScrollBarEnabled = true
        addView(stepsContainer, android.view.ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        visibility = View.GONE
    }
    val stepPlaceholder = TextView(ctx).apply {
        text = "备忘不需要步骤；切换「计划」可添加多个步骤"
        textSize = 12.5f
        setTextColor(colorRes(R.color.dsh_text_faint))
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(10), dp(8), dp(10))
        background = roundRect(colorRes(R.color.dsh_input_bg), 12, colorRes(R.color.dsh_accent_stroke))
        visibility = View.VISIBLE
    }
    val stepRows = ArrayList<EditText>()
    fun addStepRow() {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val et = EditText(ctx).apply {
            hint = "步骤内容"
            textSize = 14f
            setTextColor(colorRes(R.color.dsh_text_input))
            background = roundRect(colorRes(R.color.dsh_input_bg), 12, colorRes(R.color.dsh_accent_stroke))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setSingleLine(true)
        }
        row.addView(et, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val del = TextView(ctx).apply {
            text = "✕"
            textSize = 17f
            setTextColor(colorRes(R.color.dsh_danger))
            setPadding(dp(10), 0, dp(2), 0)
        }
        del.setOnClickListener {
            stepsContainer.removeView(row)
            stepRows.remove(et)
        }
        row.addView(del)
        stepsContainer.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) })
        stepRows.add(et)
    }
    val addStepBtn = pill("＋ 添加步骤", false) { addStepRow() }
    addStepBtn.visibility = View.GONE
    // 固定高度宿主：占位（备忘）/ 步骤区（计划）叠放，尺寸恒定
    val stepArea = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
    }
    stepArea.addView(stepPlaceholder, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    // 步骤区放进 stepsScroll（固定高度 maxStepH，多步骤就内部滚；整页不再滚）
    stepArea.addView(stepsScroll, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, maxStepH))
    stepArea.addView(addStepBtn, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(8) })
    stepHost.addView(desc("步骤"))
    stepHost.addView(stepArea)
    // 类型切换
    memoBtn.setOnClickListener {
        isPlan.set(false); paintTypeBtn(memoBtn, true); paintTypeBtn(planBtn, false)
        stepPlaceholder.visibility = View.VISIBLE
        stepsScroll.visibility = View.GONE
        addStepBtn.visibility = View.GONE
    }
    planBtn.setOnClickListener {
        isPlan.set(true); paintTypeBtn(memoBtn, false); paintTypeBtn(planBtn, true)
        stepPlaceholder.visibility = View.GONE
        stepsScroll.visibility = View.VISIBLE
        addStepBtn.visibility = View.VISIBLE
        if (stepRows.isEmpty()) addStepRow()
    }
    val body = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(12))
    }
    // ---- 重复规则 / 提前提醒：两个 chip，点开单选弹窗，纵向只占一行（不给弹窗添高）----
    val repeatVals = listOf(
        Reminder.REPEAT_NONE, Reminder.REPEAT_DAILY, Reminder.REPEAT_WEEKLY,
        Reminder.REPEAT_WEEKDAYS, Reminder.REPEAT_MONTHLY,
    )
    val repeatTexts = repeatVals.map { Reminder.repeatLabel(it) }
    val advVals = listOf(0, 5, 15, 30, 60)
    val advTexts = listOf("准时", "提前 5 分钟", "提前 15 分钟", "提前 30 分钟", "提前 1 小时")
    var selRepeat = Reminder.REPEAT_NONE
    var selAdvMin = 0
    var repeatChip: Button? = null
    var advChip: Button? = null
    repeatChip = pill("重复：不重复", false) {
        showChoiceDialog("重复", repeatTexts, repeatVals.indexOf(selRepeat)) { i ->
            selRepeat = repeatVals[i]
            repeatChip?.text = "重复：${repeatTexts[i]}"
        }
    }
    advChip = pill("提前：准时", false) {
        showChoiceDialog("提前提醒", advTexts, advVals.indexOf(selAdvMin)) { i ->
            selAdvMin = advVals[i]
            advChip?.text = "提前：${advTexts[i]}"
        }
    }
    val optRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, 0, 0, dp(10))
        addView(repeatChip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(advChip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { leftMargin = dp(8) })
    }
    body.addView(typeRow)
    body.addView(titleEdit)
    body.addView(tagsEdit)
    body.addView(optRow)
    body.addView(wheelHost)
    body.addView(stepHost)

    // ---- 自定义 Dialog：卡片圆角 + 色板色 + 自绘标题栏/按钮栏，统一 UI 风格 ----
    val card = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = roundRect(colorRes(R.color.dsh_bg_card), 24, 0)
        setPadding(dp(4), dp(4), dp(4), dp(4))
    }
    // 标题栏（标题 + 右上关闭）
    val titleBar = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(16), dp(12), dp(6))
    }
    titleBar.addView(TextView(ctx).apply {
        text = "新建提醒 / 计划"
        textSize = 17f
        setTextColor(colorRes(R.color.dsh_text_primary))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    val closeX = TextView(ctx).apply {
        text = "✕"
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(colorRes(R.color.dsh_text_hint))
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }
    titleBar.addView(closeX)
    card.addView(titleBar)
    // 内容：body 直接入卡片（不再整页滚）——步骤列表自带 150dp 局部滚动区（stepsScroll），
    // 多步骤时在步骤区内部滚，标题/滚轮/按钮始终可见，弹窗高度贴合内容、无下半空白。
    card.addView(body, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    // 按钮栏：取消 / 保存（先建按钮，点击绑定在 dlg 声明后统一做）
    val btnRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(20), dp(10), dp(20), dp(16))
    }
    val cancelBtn = pill("取消", false) { }
    val saveBtn = pill("保存", true) { }
    btnRow.addView(cancelBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    btnRow.addView(saveBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        .apply { leftMargin = dp(12) })
    card.addView(btnRow)
    // 组装 Dialog：card 高度自适应（WRAP_CONTENT）——步骤区自带 150dp 局部滚动，整页不滚、无下半空白
    val dlg = android.app.Dialog(ctx)
    dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
    dlg.setContentView(card, LinearLayout.LayoutParams(
        (ctx.resources.displayMetrics.widthPixels * 0.9).toInt(),
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ))
    dlg.window?.setLayout(
        (ctx.resources.displayMetrics.widthPixels * 0.9).toInt(),
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
    dlg.window?.setBackgroundDrawable(ColorDrawable(0x00000000.toInt()))
    // 点击绑定（此时 dlg 已声明，可安全引用；漏填不关弹窗，仅合法保存才 dismiss）
    closeX.setOnClickListener { dlg.dismiss() }
    cancelBtn.setOnClickListener { dlg.dismiss() }
    saveBtn.setOnClickListener {
        val title = titleEdit.text.toString().trim()
        if (title.isEmpty()) {
            Toast.makeText(ctx, "请填写标题", Toast.LENGTH_SHORT).show()
            titleEdit.requestFocus()
            return@setOnClickListener
        }
        val timeMs = timeWheel.picked().toEpochMilli()
        val status = if (timeMs <= System.currentTimeMillis())
            Reminder.STATUS_TRIGGERED else Reminder.STATUS_PENDING
        if (isPlan.get()) {
            val steps = stepRows.map { Reminder.Step(it.text.toString().trim(), false) }
                .filter { it.text.isNotEmpty() }
            if (steps.isEmpty()) {
                Toast.makeText(ctx, "计划至少填一个步骤", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            addReminder(Reminder.Plan("r_${System.nanoTime()}", title, timeMs, status, steps = steps,
                repeat = selRepeat, advanceMin = selAdvMin,
                tags = Reminder.parseTags(tagsEdit.text.toString())))
        } else {
            addReminder(Reminder.Memo("r_${System.nanoTime()}", title, timeMs, status,
                repeat = selRepeat, advanceMin = selAdvMin,
                tags = Reminder.parseTags(tagsEdit.text.toString())))
        }
        dlg.dismiss()
    }
    dlg.show()
}
/** 落盘一条新提醒 + 刷新 + 挂闹钟。 */
private fun addReminder(r: Reminder) {
    val list = Reminder.load(Store.readReminders(this)).toMutableList()
    list.add(r)
    Store.writeReminders(this, Reminder.save(list))
    ReminderScheduler.armAll(this)
    refreshReminderList()
    Toast.makeText(this, "已添加「${r.title}」", Toast.LENGTH_SHORT).show()
}

    /** 卡片容器：白底圆角 + 轻阴影，块内直接 addView 即可 */
    private fun card(block: LinearLayout.() -> Unit): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = roundRect(colorRes(R.color.dsh_bg_card), 20, 0)
            elevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
        }
        c.block()
        return c
    }

    /** 读崩溃日志：与 DshApp 共用同一份文件（外部目录优先，内部兑底），取第一个非空内容 */
    private fun readCrashLog(): String {
        val paths = listOfNotNull(
            getExternalFilesDir(null)?.let { File(it, DshApp.CRASH_LOG_NAME) },
            File(filesDir, DshApp.CRASH_LOG_NAME),
        )
        for (f in paths) {
            if (f.exists() && f.length() > 0) {
                val t = try { f.readText() } catch (e: Exception) { "" }
                if (t.isNotBlank()) return t
            }
        }
        return ""
    }


    /** 复制崩溃日志到剪贴板并清空，为下一次崩溃留干净记录 */
    private fun copyAndClearCrashLog() {
        val txt = readCrashLog()
        if (txt.isBlank()) {
            Toast.makeText(this, "暂无崩溃日志可复制", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("dsh-pet crash log", txt))
        clearCrashLog()
        Toast.makeText(this, "已复制到剪贴板并清空，可粘贴给我排查", Toast.LENGTH_SHORT).show()

    }
    /** modal 弹窗：白卡风格、蓝色 accent、圆角堆栈框，与 APP 风格统一 */
    private fun showCrashDialog() {
        val txt = readCrashLog()
        if (txt.isBlank()) return
        val entries = txt.split("===== ").size - 1

        // 内容根：圆角白卡
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), dp(16))
            background = roundRect(colorRes(R.color.dsh_bg_card), 20, 0)
        }
        // 标题栏
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, dp(10))
        }
        titleRow.addView(TextView(this).apply {
            text = "崩溃日志"
            textSize = 17f
            setTextColor(0xFF1A73E8.toInt())
            setPadding(0, 0, dp(10), 0)
        })
        val subtitle = TextView(this).apply {
            text = "共 $entries 条记录"
            textSize = 12f
            setTextColor(colorRes(R.color.dsh_text_hint))
        }
        titleRow.addView(subtitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(titleRow)
        // 分隔线（用 marginTop 控制间距，避免 setMargins 四参易错）
        val divider = View(this).apply {
            setBackgroundColor(colorRes(R.color.dsh_divider))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1),
            ).apply { topMargin = dp(10) }
        }
        root.addView(divider)
        // 堆栈滚动区
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(240),
            ).apply { topMargin = dp(4) }
        }
        scroll.addView(TextView(this).apply {
            text = txt
            textSize = 11f
            setTextColor(colorRes(R.color.dsh_text_input))
            typeface = android.graphics.Typeface.MONOSPACE
            background = roundRect(colorRes(R.color.dsh_input_bg), 12, colorRes(R.color.dsh_accent_stroke))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        })
        root.addView(scroll)
        // 按钮行
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        val copyBtn = pill("复制并清空", true) { }
        val clearBtn = pill("清空", false) { }
        btnRow.addView(copyBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btnRow.addView(clearBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
        root.addView(btnRow)

        // 透明 Dialog 宿主：遮罩 40%、宽 86% 屏、圆角白卡
        var dlgRef: android.app.Dialog? = null
        val dlg = android.app.Dialog(this@SettingsActivity).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(root)
            window?.apply {
                setDimAmount(0.4f)
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
                val w = (resources.displayMetrics.widthPixels * 0.86).toInt()
                setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
        dlgRef = dlg
        // 让按钮复制/清空后能关闭弹窗
        copyBtn.setOnClickListener { copyAndClearCrashLog(); dlgRef?.dismiss() }
        clearBtn.setOnClickListener { clearCrashLog(); dlgRef?.dismiss() }
        dlg.show()
    }
    private fun clearCrashLog() {
        (application as? DshApp)?.clearCrashLog()
        try { getExternalFilesDir(null)?.let { File(it, DshApp.CRASH_LOG_NAME).delete() } }
        catch (ignored: Exception) { }
        try { File(filesDir, DshApp.CRASH_LOG_NAME).delete() } catch (ignored: Exception) { }
    }

    private fun roundRect(fill: Int, radiusDp: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
            if (stroke != 0) setStroke(dp(1), stroke)
        }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(colorRes(R.color.dsh_section_title))
        letterSpacing = 0.06f
        includeFontPadding = false
        setPadding(0, 0, 0, dp(8))
    }

    private fun desc(t: String) = TextView(this).apply {
        text = t
        textSize = 12.5f
        setLineSpacing(dp(2).toFloat(), 1f)
        setTextColor(colorRes(R.color.dsh_text_hint))
        setPadding(0, 0, 0, dp(8))
    }

    private fun valueLabel() = TextView(this).apply {
        textSize = 13f
        setTextColor(colorRes(R.color.dsh_accent))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun labelRow(label: String, value: TextView) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, dp(2))
        addView(
            TextView(this@SettingsActivity).apply {
                text = label
                textSize = 14f
                setTextColor(colorRes(R.color.dsh_text_secondary))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(value)
    }

    private fun slider(max: Int) = SeekBar(this).apply {
        this.max = max
        setPadding(0, dp(4), 0, dp(4))
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                updateValueLabels()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {
                if (!loading) onUiChanged()
            }
        })
    }

    // 控件尺寸令牌：所有胶囊按钮 / tab / 次级动作统一到同一高度与圆角，避免行间参差
    private val PILL_H = 40
    private val PILL_RADIUS = 14

    private fun pill(text: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 13.5f
        isAllCaps = false
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(if (primary) Color.WHITE else colorRes(R.color.dsh_text_secondary))
        background = roundRect(
            if (primary) colorRes(R.color.dsh_accent) else colorRes(R.color.dsh_accent_soft),
            PILL_RADIUS,
            if (primary) 0 else colorRes(R.color.dsh_accent_stroke),
        )
        stateListAnimator = null
        // 用 minHeight 而不是 layoutParams 固定高度：调用方传 WRAP_CONTENT 也能拿到 40dp，
        // 传 weight/0 时也不会被覆盖（曾因写死 layoutParams 导致带权重的按钮行塔成文字高）。
        minHeight = dp(PILL_H)
        minimumHeight = dp(PILL_H)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(14), 0, dp(14), 0)
        setOnClickListener { onClick() }
    }

    private fun input(label: String, sample: String) = EditText(this).apply {
        hint = "$label（如 $sample）"
        setHintTextColor(colorRes(R.color.dsh_text_input_hint))
        setTextColor(colorRes(R.color.dsh_text_input))
        textSize = 14f
        setSingleLine(true)
        background = roundRect(colorRes(R.color.dsh_input_bg), 12, colorRes(R.color.dsh_accent_stroke))
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (loading) return
                main.removeCallbacks(saveAiRunnable)
                main.postDelayed(saveAiRunnable, 600)
            }
        })
    }

    // ------------------------------------------------------------ 权限状态

    private fun hasOverlay(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun refreshPermState() {
        val overlay = hasOverlay()
        val running = PetService.running
        val hidden = PetService.isHidden

        statusText.text = when {
            !overlay -> "缺少悬浮窗权限，桌宠无法显示"
            !running -> "桌宠已停止"
            hidden -> "桌宠运行中（已隐藏）"
            else -> "桌宠正在运行"
        }
        statusDot.background = roundRect(
            when {
                !overlay -> colorRes(R.color.dsh_status_stop)
                !running -> colorRes(R.color.dsh_dot_inactive)
                hidden -> colorRes(R.color.dsh_status_hidden)
                else -> colorRes(R.color.dsh_status_run)
            },
            99,
            0,
        )

        // 启停融合：一个按钮同时表达「现在是什么状态」+「点下去会发生什么」。
        // 运行中=红色「停止桌宠」，停止时=蓝色「启动桌宠」，没权限=灰色不可点。
        powerBtn.isEnabled = overlay
        powerBtn.text = if (running) "停止桌宠" else "启动桌宠"
        powerBtn.setTextColor(if (overlay) Color.WHITE else colorRes(R.color.dsh_text_faint))
        powerBtn.background = roundRect(
            when {
                !overlay -> colorRes(R.color.dsh_status_disabled)
                running -> colorRes(R.color.dsh_status_stop)
                else -> colorRes(R.color.dsh_accent)
            },
            14,
            0,
        )

        hideBtn.isEnabled = running
        hideBtn.text = if (hidden) "显示桌宠" else "暂时隐藏桌宠"

        // 权限按钮：绿=已授权 / 红=未授权。两种状态都可点——已授权也允许点进去撤销，
        // 用户不需要为了改回来再去找系统设置的路径。
        paintPermBtn(overlayBtn, overlay, "悬浮窗")
        // 通知状态必须看**两个开关**，不能只看 13 的运行时权限：
        //  · Android 13+ 运行时权限（POST_NOTIFICATIONS）
        //  · 应用通知总开关（NotificationManager.areNotificationsEnabled，所有版本都有）
        // EMUI / MagicOS / HyperOS 这类 ROM 把两者分开存：总开关关着时 checkSelfPermission
        // 仍然返回「已授予」，于是旧逻辑显示「已授权」但通知实际发不出来；
        // 反过来用户只在系统里开了总开关、没给运行时权限时，旧逻辑又显示「未授权」——
        // 这就是「系统里通知明明开着，这里却显示未授权」的误报来源。
        paintPermBtn(notifyBtn, notificationsReady(), "通知")

        // 后台保活：电池优化白名单是「能不能活过息屏/划掉后台」的关键开关，红绿一眼可见。
        // 自启动是 ROM 私有开关，没有可查询的官方 API，所以只给入口、不判断状态。
        val batteryOk = KeepAlive.isIgnoringBattery(this)
        paintPermBtn(batteryBtn, batteryOk, "电池优化")
        keepAliveHint.text = if (batteryOk) {
            "已允许后台运行。若仍被清理，请在「自启动管理」里把本应用设为允许，并把最近任务卡片下拉锁定。"
        } else {
            "未允许后台运行：息屏或划掉后台后可能被系统清理。点上面「电池优化」允许即可。"
        }
    }

    /**
     * 通知是否真的能弹出来：运行时权限（13+）与通知总开关（全版本）同时成立才算。
     *
     * 读不到（个别 ROM 抛异常）时按「已开启」处理——宁可少一次误报，也不要让用户
     * 明明开着通知却被告知未授权。
     */
    private fun notificationsReady(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val runtime = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!runtime) return false
        }
        return try {
            NotificationManagerCompat.from(this).areNotificationsEnabled()
        } catch (e: Exception) {
            true
        }
    }

    /**
     * 权限按钮的红绿特征。
     *
     * 之前这两个按钮只有灰底、授没授权全靠点进去才发现，用户没法一眼判断缺哪一项。
     * 现在：已授权=浅绿底+绿描边+绿字+「已授权」；未授权=浅红底+红描边+红字+「未授权」。
     */
    private fun paintPermBtn(btn: Button, granted: Boolean, label: String) {
        btn.isEnabled = true
        btn.text = if (granted) "$label ·已授权" else "$label ·未授权"
        btn.textSize = 12f
        btn.setTextColor(if (granted) colorRes(R.color.dsh_success) else colorRes(R.color.dsh_danger))
        btn.background = roundRect(
            if (granted) colorRes(R.color.dsh_success_bg) else colorRes(R.color.dsh_perm_deny_bg),
            14,
            if (granted) colorRes(R.color.dsh_perm_grant_stroke) else colorRes(R.color.dsh_perm_deny_stroke),
        )
    }

    /**
     * 跳转悬浮窗权限设置页。
     *
     * 原来点按钮是 startActivityForResult 直接拉权限页，但用户点了「去授权」之后
     * 界面没有反馈——因为授权页是系统界面，回来后 onResume 才刷一次，且如果用户
     * 只是看一眼没授权，按钮也不会有任何变化。现在统一走这里：无论授权与否都能点，
     * 进去之后改完返回，onResume + 800ms 轮询会把按钮颜色和文案改过来。
     */
    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val direct = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        try {
            startActivityForResult(direct, REQ_OVERLAY)
            return
        } catch (e: Exception) {
            Log.w("dsh-pet", "跳转悬浮窗权限页失败: ${e.message}")
        }
        // 部分 ROM（含部分 EMUI 版本）不认带 package 的写法，退回应用详情页
        try {
            startActivityForResult(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
                REQ_OVERLAY,
            )
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开权限设置，请到系统设置里手动授予", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 通知权限：能弹系统弹窗就先弹（首次最省事），弹过/已授权则跳系统通知设置页。
     *
     * Android 13 起 POST_NOTIFICATIONS 是运行时权限，用户一旦选过「不允许」，
     * 再调 requestPermissions 系统不会再弹窗（直接回调 denied），必须去设置页手动开。
     */
    private fun openNotifySettings() {
        // 13+ 且还没弹过系统弹窗：先弹一次运行时授权（对用户最省事的一步）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifyAsked) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifyAsked = true
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
                return
            }
        }
        // 所有版本都优先跳「本应用的通知设置页」。
        // 旧逻辑只在 13+ 才用这个 action，12 及以下（含 EMUI / MagicOS / HyperOS 的低版本）
        // 会一路落到应用详情页 —— 用户还得自己在列表里找「通知」，经常找不到就说「点不动」。
        try {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
            return
        } catch (e: Exception) {
            Log.w("dsh-pet", "跳转通知设置页失败: ${e.message}")
        }
        // 老 ROM 不认上面的 action：退回 API 26 之前的旧键名写法
        try {
            startActivity(
                Intent("android.settings.APP_NOTIFICATION_SETTINGS")
                    .putExtra("app_package", packageName)
                    .putExtra("app_uid", applicationInfo.uid)
            )
            return
        } catch (e: Exception) {
            Log.w("dsh-pet", "跳转通知设置页(旧键名)失败: ${e.message}")
        }
        // 兜底：应用详情页（所有 ROM 都有）
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开通知设置，请到系统设置里手动授予", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_EXPORT_REMINDERS -> {
                try {
                    val json = Store.readReminders(this)
                    contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    Toast.makeText(this, "已导出提醒", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            REQ_EXPORT_TEXT -> {
                try {
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(pendingExportText.toByteArray(Charsets.UTF_8))
                    }
                    Toast.makeText(this, "已导出", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
                }
                pendingExportText = ""
            }
            REQ_IMPORT_REMINDERS -> {
                try {
                    val text = contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: ""
                    val incoming = Reminder.load(text)
                    if (incoming.isEmpty()) {
                        Toast.makeText(this, "文件里没有可导入的提醒", Toast.LENGTH_SHORT).show()
                        return
                    }
                    // 合并去重：同 id 保留本地，新 id 追加
                    val local = Reminder.load(Store.readReminders(this)).toMutableList()
                    val ids = local.map { it.id }.toHashSet()
                    var added = 0
                    for (r in incoming) if (ids.add(r.id)) { local.add(r); added++ }
                    Store.writeReminders(this, Reminder.save(local))
                    ReminderScheduler.armAll(this)
                    refreshReminderList()
                    Toast.makeText(this, "已导入 $added 条提醒", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "导入失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFY) {
            refreshPermState()
            // 被拒时给一句可执行的指引，而不是让按钮一直红着不说话
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (!granted) Toast.makeText(this, "未授权，点「通知 ·未授权」可再次前往设置", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------ 服务控制

    /** 启停融合按钮的唯一入口：按当前真实状态决定是启动还是停止 */
    private fun onPowerClick() {
        if (!hasOverlay()) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
            openOverlaySettings()
            return
        }
        if (PetService.running) stopPet() else startPet()
    }

    private fun startPet() {
        if (!hasOverlay()) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
            return
        }
        val i = Intent(this, PetService::class.java)
        // 先落「用户希望桌宠运行」的意图并挂看门狗，再启服务：
        // 顺序反过来的话，服务刚起来就被 ROM 秒杀时会出现「意图没写、闹钟没挂 → 再也回不来」。
        KeepAlive.setWanted(this, true)
        KeepAlive.scheduleWatchdog(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        PetService.isHidden = false
        Toast.makeText(this, "桌宠已启动", Toast.LENGTH_SHORT).show()
        main.postDelayed({ refreshPermState() }, 500)
    }

    private fun stopPet() {
        // 主动停止 = 撤销保活：先清意图（onDestroy 据此不再挂闹钟），再清闹钟，最后停服务。
        // 顺序不能乱：先 stopService 的话 onDestroy 里 wanted 还是 true，会把闹钟留下来，
        // 15 分钟后桌宠自己又回来了——那就是「关不掉」。
        KeepAlive.setWanted(this, false)
        KeepAlive.cancelAll(this)
        stopService(Intent(this, PetService::class.java))
        Toast.makeText(this, "桌宠已停止", Toast.LENGTH_SHORT).show()
        main.postDelayed({ refreshPermState() }, 300)
    }

    private fun toggleHide() {
        val svc = PetService.instance
        if (svc == null) {
            Toast.makeText(this, "桌宠未运行", Toast.LENGTH_SHORT).show()
            return
        }
        val next = !PetService.isHidden
        svc.setPetHidden(next)
        PetService.isHidden = next
        Toast.makeText(
            this,
            if (next) "已隐藏，可从通知栏或本页恢复" else "已恢复显示",
            Toast.LENGTH_SHORT,
        ).show()
        main.postDelayed({ refreshPermState() }, 200)
    }

    // -------------------------------------------------------- UI 设置持久化

    private val saveUiRunnable = Runnable { persistUi() }
    private val saveAiRunnable = Runnable { saveAiSettings(true) }

    /**
     * 完全体模式开关。
     *
     * 打开后窗口改用无障碍覆盖层（TYPE_ACCESSIBILITY_OVERLAY），不再受 Android 12+
     * 「不可信触摸遮挡」限制：透明度可以真到 100%，桌面照常可点。
     * 前置条件是系统设置里的无障碍服务必须开启，所以这里顺手把用户送到那个页面。
     */
    private fun onTrustedChanged(checked: Boolean) {
        // 只改 ui-settings.json 里的一个字段，其他偏好原样保留
        val obj = try {
            JSONObject(Store.readUiSettings(this))
        } catch (e: Exception) {
            JSONObject()
        }
        obj.put("trustedOverlay", checked)
        Store.writeUiSettings(this, obj.toString())
        refreshTrustedUi()
        // 窗口类型 addView 之后改不了，必须重建（宠物会回到默认位置）
        PetService.instance?.requestRebuild()
        if (checked && !PetA11yService.isEnabled()) {
            Toast.makeText(this, "还需在系统设置里开启「${getString(R.string.app_name)}」无障碍服务", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                Log.w("dsh-pet", "打开无障碍设置失败：${e.message}")
            }
        }
    }

    /** 刷新完全体模式的说明文案 + 透明度量程（开关或无障碍状态一变就得重算） */
    private fun refreshTrustedUi() {
        if (!::trustedSwitch.isInitialized) return
        val requested = trustedRequested()
        val enabled = PetA11yService.isEnabled()
        val active = requested && enabled
        if (trustedSwitch.isChecked != requested) {
            val old = loading
            loading = true
            trustedSwitch.isChecked = requested
            loading = old
        }
        val appName = getString(R.string.app_name)
        trustedHint.text = when {
            active -> "已生效：窗口是无障碍覆盖层，透明度可到 100%，桌面照常可点。"
            requested && !enabled -> "开关已打开，但无障碍服务还没开。去系统设置 → 无障碍 → 已下载的服务，找到「$appName」并开启。"
            else -> "打开后可把透明度拉到 100% 且不影响桌面点击。需要开启系统无障碍服务（不读屏、不注入事件）。"
        }
        // 量程变了：按当前实际透明度反推滑块位置，别让数字自己跳
        val max = opacityMax
        val clamped = opacityValue.coerceIn(opacityMin, max)
        val oldLoading = loading
        loading = true
        opacitySeek.progress =
            Math.round((clamped - opacityMin) / (max - opacityMin) * 100).toInt().coerceIn(0, 100)
        loading = oldLoading
        opacityValue = clamped
        updateValueLabels()
        opacityHint?.text = if (active) {
            "完全体模式已生效：100% 不透明也不影响桌面点击。"
        } else {
            "上限 ${Math.round(max * 100)}% 是系统限制：Android 12 起窗口再高会被判为遮挡桌面，桌面点击会被直接丢弃。打开下面的「完全体模式」可解除。"
        }
    }

    private fun onUiChanged() {
        updateValueLabels()
        main.removeCallbacks(saveUiRunnable)
        main.postDelayed(saveUiRunnable, 250)
    }

    private fun updateValueLabels() {
        val ratio = sizeMin + sizeSeek.progress / 100.0 * (sizeMax - sizeMin)
        sizeVal.text = "${Math.round(ratio * 100)}% 屏宽"
        val op = opacityMin + opacitySeek.progress / 100.0 * (opacityMax - opacityMin)
        opacityValue = op
        opacityVal.text = "${Math.round(op * 100)}%"
        countVal.text = "${countSeek.progress + 1} 只"
    }

    private fun loadUiSettings() {
        val j = try {
            JSONObject(Store.readUiSettings(this))
        } catch (e: Exception) {
            JSONObject()
        }
        val ratio = j.optDouble("sizeRatio", 0.42).coerceIn(sizeMin, sizeMax)
        sizeSeek.progress = Math.round((ratio - sizeMin) / (sizeMax - sizeMin) * 100).toInt().coerceIn(0, 100)
        trustedSwitch.isChecked = j.optBoolean("trustedOverlay", false)
        opacityValue = j.optDouble("opacity", 1.0).coerceIn(opacityMin, opacityMax)
        opacitySeek.progress =
            Math.round((opacityValue - opacityMin) / (opacityMax - opacityMin) * 100).toInt().coerceIn(0, 100)
        countSeek.progress = (j.optInt("petCount", 1) - 1).coerceIn(0, 2)
        soundSwitch.isChecked = j.optBoolean("clickSound", true)

        val arr = j.optJSONArray("sayings") ?: JSONArray()
        val lines = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "").trim()
            if (s.isNotEmpty()) lines.add(s)
        }
        sayingsEdit.setText(lines.joinToString("\n"))
        sayingsCombineSwitch.isChecked = j.optBoolean("sayingsCombine", false)
        // 量程/文案取决于开关与无障碍状态，统一在这里刷一次
        refreshTrustedUi()
    }

    private fun persistUi() {
        val ratio = sizeMin + sizeSeek.progress / 100.0 * (sizeMax - sizeMin)
        val op = opacityMin + opacitySeek.progress / 100.0 * (opacityMax - opacityMin)
        val sayings = JSONArray()
        sayingsEdit.text.toString().split('\n').forEach { line ->
            val s = line.trim()
            if (s.isNotEmpty()) sayings.put(s)
        }
        val obj = JSONObject()
            .put("sizeRatio", Math.round(ratio * 1000) / 1000.0)
            .put("opacity", Math.round(op * 100) / 100.0)
            .put("clickSound", soundSwitch.isChecked)
            .put("petCount", countSeek.progress + 1)
            .put("trustedOverlay", trustedSwitch.isChecked)
            .put("sayings", sayings)
            .put("sayingsCombine", sayingsCombineSwitch.isChecked)
        if (!Store.writeUiSettings(this, obj.toString())) {
            Toast.makeText(this, "外观设置保存失败，请重试", Toast.LENGTH_SHORT).show()
            return
        }
        PetService.instance?.reloadConfig()
    }

    // ------------------------------------------------------------ AI 设置

    private fun loadAiSettings() {
        val json = try {
            JSONObject(Store.readAiSettings(this))
        } catch (e: Exception) {
            JSONObject()
        }
        aiEnabled.isChecked = json.optBoolean("enabled", false)
        baseUrlEdit.setText(json.optString("baseUrl", ""))
        modelEdit.setText(json.optString("model", ""))
        apiKeyEdit.setText(json.optString("apiKey", ""))
    }

    /**
     * 写 ai-settings.json 并让桌宠重读配置。
     *
     * @param silent true = 输入过程中的自动保存（只更新小字提示，不弹 Toast 打断输入）
     */
    private fun saveAiSettings(silent: Boolean) {
        val obj = JSONObject()
            .put("enabled", aiEnabled.isChecked)
            .put("provider", "openai-compatible")
            .put("baseUrl", baseUrlEdit.text.toString().trim())
            .put("model", modelEdit.text.toString().trim())
            .put("apiKey", apiKeyEdit.text.toString().trim())
            .put("maxTokens", 1024)
        if (!Store.writeAiSettings(this, obj.toString())) {
            aiHint.setTextColor(0xFFC0392B.toInt())
            aiHint.text = "保存失败：无法写入应用私有目录"
            return
        }
        PetService.instance?.reloadConfig()
        val missing = obj.optString("baseUrl").isEmpty() || obj.optString("model").isEmpty()
        aiHint.setTextColor(if (missing) 0xFFB07A00.toInt() else colorRes(R.color.dsh_success))
        aiHint.text = if (missing) {
            "已保存。还差接口地址或模型名，填完才能对话。"
        } else {
            "已自动保存 · 桌宠已重读配置"
        }
        if (!silent) Toast.makeText(this, getString(R.string.settings_ai_saved), Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------ 关于与更新

    /**
     * 查 GitHub Releases 有没有新版本。
     *
     * 逻辑全在 [UpdateChecker] 里，这里只负责三件事：防连点（GitHub 未认证限额
     * 60 次/小时/IP）、把按钮置灰、把结果翻译成用户能看懂的一句话。
     *
     * @param silent true = 静默预查（只更新小字，不弹窗、不打断用户）
     */
    private fun checkUpdate(silent: Boolean) {
        if (checkingUpdate) return
        checkingUpdate = true
        if (::updateBtn.isInitialized) {
            updateBtn.isEnabled = false
            updateBtn.text = "检查中…"
        }
        if (::updateHint.isInitialized) {
            updateHint.setTextColor(colorRes(R.color.dsh_text_hint))
            updateHint.text = "正在查询 GitHub…"
        }
        UpdateChecker.check(this) { result, error ->
            checkingUpdate = false
            if (::updateBtn.isInitialized) {
                updateBtn.isEnabled = true
                updateBtn.text = "检查更新"
            }
            val current = UpdateChecker.currentVersion(this)
            val r = result
            if (!::updateHint.isInitialized) return@check
            when {
                error != null -> {
                    updateHint.setTextColor(0xFFC0392B.toInt())
                    updateHint.text = "检查失败：$error"
                    if (!silent) Toast.makeText(this, "检查失败：$error", Toast.LENGTH_LONG).show()
                }
                r == null -> {
                    updateHint.setTextColor(0xFFC0392B.toInt())
                    updateHint.text = "检查失败：没有拿到有效返回"
                }
                r.newer -> {
                    updateHint.setTextColor(colorRes(R.color.dsh_accent))
                    updateHint.text = "发现新版本 ${r.tag}（当前 $current）"
                    showUpdateDialog(r, current)
                }
                else -> {
                    updateHint.setTextColor(colorRes(R.color.dsh_success))
                    updateHint.text = "已是最新版本（$current）· 最新发布 ${r.tag}"
                    if (!silent) Toast.makeText(this, "已是最新版本", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 有新版本时：自定义美化卡片（版本胶囊 + 更新日志 + 双通道更新方式 + 下载进度）。
     *
     * 两条「更新方式」并列，语义分清：
     *  · **App 内下载安装**（主推荐）：走 [UpdateInstaller] 下到私有目录再交系统安装器，
     *    全程留在应用内，带百分比进度条；
     *  · **浏览器打开**：走 [openUrl] 跳到 Release 页 / APK 直链，由浏览器下载，适合
     *    想手动挑文件、或设备没装成「允许未知来源」的场景。
     * 卡片用透明 Dialog 宿主 + 白卡（与崩溃日志弹窗同一套风格），宽 88% 屏。
     */
    private fun showUpdateDialog(r: UpdateChecker.Result, current: String) {
        val notes = r.notes.trim()
        val apk = r.apkUrl

        // ---- 卡片根：白底圆角 ----
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(20))
            background = roundRect(colorRes(R.color.dsh_bg_card), 22, 0)
        }

        // NEW 标签 + 标题
        val iconBlock = TextView(this).apply {
            text = "NEW"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = roundRect(colorRes(R.color.dsh_accent), 9, 0)
            setPadding(dp(9), dp(4), dp(9), dp(4))
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(iconBlock, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { rightMargin = dp(10) })
        titleRow.addView(TextView(this).apply {
            text = "发现新版本"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorRes(R.color.dsh_text_primary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(titleRow)

        // 版本对比：当前 → 最新
        val verRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        verRow.addView(versionPill(current, colorRes(R.color.dsh_dot_inactive), colorRes(R.color.dsh_accent_soft)),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        verRow.addView(TextView(this).apply {
            text = "→"
            textSize = 16f
            setTextColor(colorRes(R.color.dsh_accent))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), 0, dp(10), 0)
        })
        verRow.addView(versionPill(r.tag, colorRes(R.color.dsh_accent), colorRes(R.color.dsh_focus_highlight)),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(verRow)

        // 更新日志（可滚动）
        if (notes.isNotEmpty()) {
            card.addView(TextView(this).apply {
                text = "更新日志"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(colorRes(R.color.dsh_section_title))
                setPadding(0, dp(16), 0, dp(4))
            })
            val logScroll = ScrollView(this).apply {
                isVerticalScrollBarEnabled = true
                setBackgroundColor(colorRes(R.color.dsh_input_bg))
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = roundRect(colorRes(R.color.dsh_input_bg), 12, colorRes(R.color.dsh_divider))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(140),
                )
            }
            logScroll.addView(TextView(this).apply {
                text = if (notes.length > 500) notes.take(500) + "…" else notes
                textSize = 13f
                setTextColor(0xFF3A4258.toInt())
                setLineSpacing(0f, 1.3f)
            })
            card.addView(logScroll)
        }

        // 更新方式
        card.addView(TextView(this).apply {
            text = "选择更新方式"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorRes(R.color.dsh_section_title))
            setPadding(0, dp(16), 0, dp(8))
        })

        // 通道 1：App 内下载安装（主推荐 · 断点续传 + 已下载立即安装）
        var downloadStarted = false
        val inAppBtn = updateModeCard("", "", true)
        inAppBtn.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        card.addView(inAppBtn)

        // 「重新下载」小链接：清除断点缓存从头下（仅当已有本地缓存时显示）
        val resetLink = TextView(this).apply {
            text = "重新下载（清除本地缓存）"
            textSize = 11.5f
            setTextColor(0xFF8A93A6.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
            isVisible = false
        }
        card.addView(resetLink)

        // 改 inAppBtn 内部标题/副标题（updateModeCard 层级：container[0]=textCol，textCol[0]=标题，textCol[1]=副标题）
        fun setInAppLabel(title: String, sub: String) {
            val container = inAppBtn as? LinearLayout ?: return
            val textCol = container.getChildAt(0) as? LinearLayout ?: return
            (textCol.getChildAt(0) as? TextView)?.text = title
            (textCol.getChildAt(1) as? TextView)?.text = sub
        }

        val progress = ProgressBar(this, null, 0, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isVisible = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6),
            ).apply { topMargin = dp(10) }
        }
        card.addView(progress)
        val progressLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(colorRes(R.color.dsh_accent))
            isVisible = false
            setPadding(0, dp(4), 0, 0)
            gravity = Gravity.CENTER
        }
        card.addView(progressLabel)

        // 通道 2：浏览器打开（次级）
        val browserBtn = updateModeCard("浏览器打开发布页", "在浏览器里下载 APK 或查看完整版本", false)
        browserBtn.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) }
        card.addView(browserBtn)

        // 稍后
        var dlgRef: Dialog? = null
        val laterBtn = pill("稍后", false) { }
        laterBtn.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) }
        card.addView(laterBtn)

        // 透明 Dialog 宿主（先建好，稍后按钮用它 dismiss）
        val dlg = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(card)
            window?.apply {
                setDimAmount(0.5f)
                setBackgroundDrawable(ColorDrawable(0x00000000))
                setLayout((resources.displayMetrics.widthPixels * 0.88).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
        dlgRef = dlg
        laterBtn.setOnClickListener { dlgRef?.dismiss() }

        // 打开弹窗时：按本地缓存状态预置按钮文案（有完整包→"立即安装"，有断点→"继续下载"，都没有→"开始下载"）
        val completedFile = if (apk != null) UpdateInstaller.findCompleted(this, apk) else null
        val partial = if (apk != null) UpdateInstaller.findPartial(this, apk) else null
        val hasLocalCache = completedFile != null || partial != null
        resetLink.isVisible = hasLocalCache
        if (apk == null) {
            setInAppLabel("App 内下载安装", "该版本没有可直下的 APK，请用浏览器")
            inAppBtn.isEnabled = false
            inAppBtn.alpha = 0.55f
        } else when {
            completedFile != null -> {
                setInAppLabel("已下载 · 立即安装", "${completedFile.length() / 1024 / 1024} MB · 点我直接装，不用重下")
                if (partial != null) { /* 完整已就位，断点忽略 */ }
            }
            partial != null -> {
                setInAppLabel("继续下载", "已下 ${partial.second / 1024} KB，断点续传")
                progress.isVisible = true
                progressLabel.isVisible = true
                progressLabel.text = "已从断点恢复（已下 ${partial.second / 1024} KB）"
            }
            else -> setInAppLabel("App 内下载安装", "推荐 · 断点续传 · 下载后引导安装")
        }

        inAppBtn.setOnClickListener {
            if (apk == null) {
                Toast.makeText(this, "该版本没有可直下的 APK，已改打开发布页", Toast.LENGTH_LONG).show()
                openUrl(r.pageUrl)
                return@setOnClickListener
            }
            if (downloadStarted) return@setOnClickListener
            downloadStarted = true
            inAppBtn.isEnabled = false
            inAppBtn.alpha = 0.55f
            progress.isVisible = true
            progressLabel.isVisible = true
            // 完整包已就位 → 直接装（不重下）；否则走 downloadAndInstall（内部自动判断断点/重头）
            val existing = UpdateInstaller.findCompleted(this, apk)
            if (existing != null) {
                progress.progress = 100
                progressLabel.setTextColor(colorRes(R.color.dsh_success))
                progressLabel.text = "本地已有完整包，直接安装…"
                main.post { UpdateInstaller.install(this, existing) }
            } else {
                progressLabel.setTextColor(colorRes(R.color.dsh_accent))
                progressLabel.text = "正在下载… 0%"
                UpdateInstaller.downloadAndInstall(
                    this, apk,
                    { percent, finished ->
                        progress.progress = percent
                        progressLabel.text =
                            if (finished) "下载完成，正在打开安装器…" else "正在下载… $percent%"
                    },
                    { file, msg ->
                        if (msg != null) {
                            // 出错：保留断点（.part 还在），按钮恢复，用户再点自动续传
                            downloadStarted = false
                            inAppBtn.isEnabled = true
                            inAppBtn.alpha = 1f
                            progress.isVisible = false
                            progressLabel.isVisible = true
                            progressLabel.setTextColor(0xFFC0392B.toInt())
                            progressLabel.text = msg + " · 再点继续"
                            // 断点还在 → 按钮变"继续下载"
                            val p = UpdateInstaller.findPartial(this, apk)
                            if (p != null) {
                                setInAppLabel("继续下载", "已下 ${p.second / 1024} KB，断点续传")
                            }
                            return@downloadAndInstall
                        }
                        // 成功：file 在盘上
                        progressLabel.isVisible = true
                        progressLabel.setTextColor(colorRes(R.color.dsh_success))
                        progressLabel.text = "已下载，按系统提示安装即可（若被要求授权，回来点下方再装一次）"
                        // 文件仍在盘 → 允许"授权回来后直接再装一次"
                        downloadStarted = false
                        inAppBtn.isEnabled = true
                        inAppBtn.alpha = 1f
                        setInAppLabel("已下载 · 立即安装", "授权后点我直接装，不用重下")
                    }
                )
            }
        }

        // 「重新下载」小链接：清缓存后强制从头下
        resetLink.setOnClickListener {
            if (apk == null) return@setOnClickListener
            UpdateInstaller.clearCache(this, apk)
            progress.isVisible = false
            progressLabel.isVisible = false
            setInAppLabel("App 内下载安装", "已清除本地缓存，将重新下载")
            inAppBtn.isEnabled = true
            inAppBtn.alpha = 1f
            downloadStarted = false
        }
        browserBtn.setOnClickListener { openUrl(r.pageUrl) }

        dlg.show()
    }

    /** 版本胶囊：浅底描边 + 加粗，[oldColor] 当前版本、强调色最新版 */
    private fun versionPill(label: String, textColor: Int, bgColor: Int) = TextView(this).apply {
        text = label
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(textColor)
        background = roundRect(bgColor, 99, 0)
        setPadding(dp(14), dp(5), dp(14), dp(5))
    }

    /** 「更新方式」卡片按钮：标题 + 副标题 + 右侧箭头，主/次两档配色 */
    private fun updateModeCard(title: String, sub: String, primary: Boolean = true): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundRect(
                if (primary) colorRes(R.color.dsh_accent) else colorRes(R.color.dsh_accent_soft),
                14,
                if (primary) 0 else colorRes(R.color.dsh_accent_stroke),
            )
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(this).apply {
            text = title
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (primary) Color.WHITE else colorRes(R.color.dsh_text_secondary))
        })
        textCol.addView(TextView(this).apply {
            text = sub
            textSize = 11.5f
            setTextColor(if (primary) 0xCcFFFFFF.toInt() else colorRes(R.color.dsh_text_hint))
        })
        container.addView(textCol)
        val chevron = TextView(this).apply {
            text = "›"
            textSize = 24f
            setTextColor(if (primary) Color.WHITE else colorRes(R.color.dsh_accent))
        }
        container.addView(chevron)
        return container
    }

    /** 用浏览器打开链接；设备上没有浏览器时只提示，不崩 */
    private fun openUrl(url: String) {
        if (url.isBlank()) {
            Toast.makeText(this, "链接为空，打不开", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Log.w("dsh-pet", "打开链接失败：$url（${e.message}）")
            Toast.makeText(this, "打不开链接：这台设备上没有可用的浏览器", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 开源许可与引用项目。
     *
     * 主次分两层，别混在一起：
     *  - **一、直接基于**：上游项目，没有它就没有这个 App（渲染层 / 动画 / 素材）
     *  - **二、第三方依赖**：通用库（AndroidX / OkHttp），只用公开 API，没改源码
     * 每一项点一下直达对应仓库；本应用自身也以 MIT 开源，链接放最上面。
     */
    private fun showOssDialog() {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), 0)
        }

        fun line(text: String, size: Float, color: Int, top: Int = 0): TextView =
            TextView(this).apply {
                this.text = text
                textSize = size
                setTextColor(color)
                setPadding(0, dp(top), 0, 0)
            }

        fun heading(text: String, top: Int): TextView = line(text, 13f, colorRes(R.color.dsh_text_primary), top).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        // 本应用自身
        body.addView(line("本应用：dsh-pet-android", 14f, colorRes(R.color.dsh_text_primary), 0).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        body.addView(line("MIT License · 点这里打开本仓库", 12.5f, colorRes(R.color.dsh_accent)).apply {
            setOnClickListener { openUrl(UpdateChecker.repoUrl) }
        })

        // 一、直接基于
        body.addView(heading("一、直接基于（上游项目）", 14))
        body.addView(line("界面渲染层、桌宠动画与素材都来自它，Android 侧只做平台桥接。", 12f, colorRes(R.color.dsh_text_hint), 2))
        addOssItem(
            body,
            "dsh-pet · PC2005-cloud",
            "MIT License · Web 渲染层 / 桌宠动画 / 素材",
            "https://github.com/PC2005-cloud/dsh-pet",
        )

        // 二、第三方依赖
        body.addView(heading("二、第三方依赖", 16))
        body.addView(line("只调用公开 API，未修改其源码。", 12f, colorRes(R.color.dsh_text_hint), 2))
        addOssItem(
            body,
            "AndroidX Core / AppCompat / Activity",
            "Apache License 2.0 · 基础组件与兼容层",
            "https://github.com/androidx/androidx",
        )
        addOssItem(
            body,
            "AndroidX WebKit",
            "Apache License 2.0 · WebViewAssetLoader（assets 素材映射）",
            "https://github.com/androidx/androidx",
        )
        addOssItem(
            body,
            "OkHttp 4.12",
            "Apache License 2.0 · AI 对话与更新检查的 HTTP 客户端",
            "https://github.com/square/okhttp",
        )
        addOssItem(
            body,
            "Kotlin / Android SDK",
            "Apache License 2.0 · 语言与平台运行时",
            "https://github.com/JetBrains/kotlin",
        )

        AlertDialog.Builder(this)
            .setTitle("开源许可与引用项目")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("关闭", null)
            .show()
    }

    /** 开源引用里的一项：标题（蓝色可点）+ 许可与用途小字 */
    private fun addOssItem(parent: LinearLayout, title: String, desc: String, url: String) {
        parent.addView(TextView(this).apply {
            text = title
            textSize = 13.5f
            setTextColor(colorRes(R.color.dsh_accent))
            setPadding(0, dp(10), 0, 0)
            setOnClickListener { openUrl(url) }
        })
        parent.addView(TextView(this).apply {
            text = desc
            textSize = 12f
            setTextColor(colorRes(R.color.dsh_text_hint))
        })
    }
}