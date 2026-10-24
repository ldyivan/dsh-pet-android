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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

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

    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var powerBtn: Button
    private lateinit var hideBtn: Button
    private lateinit var overlayBtn: Button
    private lateinit var notifyBtn: Button

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

    private val main = Handler(Looper.getMainLooper())
    private val REQ_NOTIFY = 1001
    private val REQ_OVERLAY = 1002

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

    private val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }

    private fun buildUi(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(32))
            setBackgroundColor(0xFFF3F5FB.toInt())
        }

        // ---------------- 头部 ----------------
        root.addView(TextView(this).apply {
            text = getString(R.string.settings_title)
            textSize = 26f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF141A2B.toInt())
        })
        root.addView(TextView(this).apply {
            text = "蓝色大肥鱼 · 桌面宠物"
            textSize = 13f
            setTextColor(0xFF7A839A.toInt())
            setPadding(0, dp(4), 0, dp(2))
        })

        // ---------------- 运行状态 ----------------
        root.addView(card {
            val row = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            statusDot = View(this@SettingsActivity).apply {
                background = roundRect(0xFFB9C0D0.toInt(), 99, 0)
            }
            row.addView(statusDot, LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(8) })
            statusText = TextView(this@SettingsActivity).apply {
                textSize = 14f
                setTextColor(0xFF2A3244.toInt())
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

        // ---------------- 外观 ----------------
        root.addView(card {
            addView(sectionTitle("外观"))
            addView(desc("调完立刻生效，不用点保存。"))

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
                setTextColor(0xFF2A3244.toInt())
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
                setTextColor(0xFF2A3244.toInt())
                setPadding(0, dp(4), 0, dp(4))
                setOnCheckedChangeListener { _, _ -> if (!loading) onUiChanged() }
            }
            addView(soundSwitch)

            addView(TextView(this@SettingsActivity).apply {
                text = "点击台词库（每行一句，点宠物时随机弹一句；留空则不弹）"
                textSize = 12.5f
                setTextColor(0xFF7A839A.toInt())
                setPadding(0, dp(10), 0, dp(6))
            })
            sayingsEdit = EditText(this@SettingsActivity).apply {
                hint = "主人，欢迎回来～\n今天也要好好休息哦\n需要我帮忙吗？"
                setHintTextColor(0xFFAEB6C6.toInt())
                setTextColor(0xFF1F2637.toInt())
                textSize = 14f
                gravity = Gravity.TOP
                minLines = 3
                maxLines = 8
                background = roundRect(0xFFFFFFFF.toInt(), 14, 0xFFDCE2EF.toInt())
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
        })

        // ---------------- AI ----------------
        root.addView(card {
            addView(sectionTitle("AI 对话"))
            aiEnabled = Switch(this@SettingsActivity).apply {
                text = getString(R.string.settings_ai_enabled)
                textSize = 14f
                setTextColor(0xFF2A3244.toInt())
                setPadding(0, dp(4), 0, dp(6))
                setOnCheckedChangeListener { _, _ -> if (!loading) saveAiSettings(false) }
            }
            addView(aiEnabled)

            addView(desc("OpenAI 兼容接口。填完自动保存，切回桌面即可用（无需重启桌宠）。"))

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
                setTextColor(0xFF3B7A57.toInt())
                setPadding(0, dp(8), 0, 0)
            }
            addView(aiHint)
        })

        // ---------------- 关于 ----------------
        root.addView(card {
            addView(sectionTitle(getString(R.string.settings_license)))
            addView(desc(getString(R.string.settings_license_body)))
        })

        return ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundColor(0xFFF3F5FB.toInt())
            isFillViewport = true
        }
    }

    /** 卡片容器：白底圆角 + 轻阴影，块内直接 addView 即可 */
    private fun card(block: LinearLayout.() -> Unit): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = roundRect(0xFFFFFFFF.toInt(), 20, 0)
            elevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14) }
        }
        c.block()
        return c
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
        setTextColor(0xFF5B6B8C.toInt())
        letterSpacing = 0.06f
        setPadding(0, 0, 0, dp(6))
    }

    private fun desc(t: String) = TextView(this).apply {
        text = t
        textSize = 12.5f
        setTextColor(0xFF7A839A.toInt())
        setPadding(0, 0, 0, dp(6))
    }

    private fun valueLabel() = TextView(this).apply {
        textSize = 13f
        setTextColor(0xFF3B6FD6.toInt())
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
                setTextColor(0xFF2A3244.toInt())
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

    private fun pill(text: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 13.5f
        isAllCaps = false
        setTextColor(if (primary) Color.WHITE else 0xFF2A3244.toInt())
        background = roundRect(
            if (primary) 0xFF3B6FD6.toInt() else 0xFFEDF1F9.toInt(),
            14,
            if (primary) 0 else 0xFFDCE2EF.toInt(),
        )
        stateListAnimator = null
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(6), dp(10), dp(6), dp(10))
        setOnClickListener { onClick() }
    }

    private fun input(label: String, sample: String) = EditText(this).apply {
        hint = "$label（如 $sample）"
        setHintTextColor(0xFFAEB6C6.toInt())
        setTextColor(0xFF1F2637.toInt())
        textSize = 14f
        setSingleLine(true)
        background = roundRect(0xFFF7F9FD.toInt(), 12, 0xFFDCE2EF.toInt())
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
                !overlay -> 0xFFE05A5A.toInt()
                !running -> 0xFFB9C0D0.toInt()
                hidden -> 0xFFF0B429.toInt()
                else -> 0xFF34C759.toInt()
            },
            99,
            0,
        )

        // 启停融合：一个按钮同时表达「现在是什么状态」+「点下去会发生什么」。
        // 运行中=红色「停止桌宠」，停止时=蓝色「启动桌宠」，没权限=灰色不可点。
        powerBtn.isEnabled = overlay
        powerBtn.text = if (running) "停止桌宠" else "启动桌宠"
        powerBtn.setTextColor(if (overlay) Color.WHITE else 0xFF9AA3B5.toInt())
        powerBtn.background = roundRect(
            when {
                !overlay -> 0xFFE4E8F1.toInt()
                running -> 0xFFE05A5A.toInt()
                else -> 0xFF3B6FD6.toInt()
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
        btn.setTextColor(if (granted) 0xFF2E7D4F.toInt() else 0xFFC53030.toInt())
        btn.background = roundRect(
            if (granted) 0xFFE8F7EE.toInt() else 0xFFFDECEC.toInt(),
            14,
            if (granted) 0xFF9BD9B4.toInt() else 0xFFF3B4B4.toInt(),
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        PetService.isHidden = false
        Toast.makeText(this, "桌宠已启动", Toast.LENGTH_SHORT).show()
        main.postDelayed({ refreshPermState() }, 500)
    }

    private fun stopPet() {
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
        aiHint.setTextColor(if (missing) 0xFFB07A00.toInt() else 0xFF3B7A57.toInt())
        aiHint.text = if (missing) {
            "已保存。还差接口地址或模型名，填完才能对话。"
        } else {
            "已自动保存 · 桌宠已重读配置"
        }
        if (!silent) Toast.makeText(this, getString(R.string.settings_ai_saved), Toast.LENGTH_SHORT).show()
    }
}