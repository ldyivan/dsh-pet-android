package com.dsh.pet

import android.content.Context
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject

/**
 * JS 侧 window.DshNative 的 Kotlin 实现。
 *
 * 契约与 android/native.ts 的 NativeApi 一一对应（13 方法）：平台能力缺失时**显式失败**，
 * 不静默伪造数据（与源项目同一原则）。素材见 https://github.com/PC2005-cloud/dsh-pet（MIT）。
 *
 * 注意：@JavascriptInterface 方法在 WebView 的 JavaBridge 线程被调用，全部转发到
 * Service 的 IO/主线程，不在此线程做重活。
 */
class NativeBridge(private val svc: PetService) {

    /** 读用户层配置 main-config.jsonc（无文件返回空串） */
    @JavascriptInterface
    fun readUserConfig(): String = Store.readUserConfig(svc)

    /** 写用户层配置（原子写） */
    @JavascriptInterface
    fun writeUserConfig(json: String): Boolean = Store.writeUserConfig(svc, json)

    /** 读对话记忆 memory.json */
    @JavascriptInterface
    fun readMemory(): String = Store.readMemory(svc)

    @JavascriptInterface
    fun writeMemory(json: String): Boolean = Store.writeMemory(svc, json)

    /** AI 设置 JSON（provider/baseUrl/model/apiKey/enabled/maxTokens） */
    @JavascriptInterface
    fun readAiSettings(): String = Store.readAiSettings(svc)

    /**
     * UI 偏好 JSON：{sizeRatio, opacity, clickSound, petCount, sayings[]}。
     * 渲染层（android/host.ts uiSettings()）负责夹取与兜底，这里只做透明存取。
     *
     * 台词库内置兜底（不重建 dsh-pet.js，纯在 Kotlin 读出口合并，host 自动消费）：
     *  · 用户没填自定义（sayings 为空）        → 用 [defaultSayings] 默认库
     *  · 用户填了自定义 + sayingsCombine=false → 仅用用户自定义库
     *  · 用户填了自定义 + sayingsCombine=true  → 默认库 + 用户自定义（去重合并）
     *  合并结果只作用于 sayings 字段，其它字段原样透传。
     */
    @JavascriptInterface
    fun readUiSettings(): String {
        val raw = Store.readUiSettings(svc)
        return try {
            val j = if (raw.isBlank()) JSONObject() else JSONObject(raw)
            val custom = ArrayList<String>()
            val arr = j.optJSONArray("sayings")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i, "").trim()
                    if (s.isNotEmpty()) custom.add(s)
                }
            }
            val combine = j.optBoolean("sayingsCombine", false)
            val merged = when {
                custom.isEmpty() -> defaultSayings
                combine -> (defaultSayings + custom).distinct()
                else -> custom
            }
            j.put("sayings", JSONArray(merged))
            j.toString()
        } catch (e: Exception) {
            raw
        }
    }

    @JavascriptInterface
    fun writeUiSettings(json: String): Boolean = Store.writeUiSettings(svc, json)

    /** 点击音效（受 ui-settings.json 的 clickSound 开关控制，由 Service 侧判定） */
    @JavascriptInterface
    fun clickSound() = svc.playClickSound()

    /**
     * 调用模型：payload = {kind:'whisper'|'chat', system, messages, maxTokens}。
     * 立即返回请求号 id；结果由 Kotlin 异步回调 window.__dshLlmResult(id, json)。
     * 失败返回结构化 JSON（ok:false + reason），绝不伪造文本。
     */
    @JavascriptInterface
    fun llm(payload: String): String = LlmClient.enqueue(svc, payload)

    /** 系统通知 */
    @JavascriptInterface
    fun notify(title: String, text: String) = svc.showPetNotification(title, text)

    /**
     * 触摸层窗口跟随：命中区包围盒，**视口归一化比例（0..1）**。
     * 参数用 Double 接收——JS number 一律映射为 double，声明 int 在非整数值时会抛类型错误。
     * 归一化 → 设备 px 的换算在 PetService 里做（乘真实屏幕尺寸），两端不各算一套。
     */
    @JavascriptInterface
    fun syncTouchLayer(x: Double, y: Double, w: Double, h: Double) =
        svc.applyTouchLayerRect(x, y, w, h)

    /**
     * 触摸层窗口跟随（多块）：`[{"x":..,"y":..,"w":..,"h":..}, ...]`，视口归一化比例。
     *
     * 每个命中元素一块，宿主为每块开一个独立悬浮窗。这是「退到桌面点不动其它 App」
     * 的结构性修复——旧版把多元素并集当一块上报，并集会横跨整屏。
     */
    @JavascriptInterface
    fun applyTouchLayersJson(json: String) = svc.applyTouchLayersJson(json)

    /**
     * 显示 / 暂时隐藏桌宠。
     *
     * 必须走这里而不是写配置 display:'none'：隐藏真值是服务端 petHidden（决定窗口 GONE
     * 与触摸层收回），配置里写 display 只会让渲染层自己把 DOM 摘掉，恢复时重读配置
     * 依然是 'none'，宠物永远回不来（用户反馈的「暂时隐藏后无法恢复」）。
     */
    @JavascriptInterface
    fun setPetHidden(hidden: Boolean) = svc.setPetHidden(hidden)

    /** 日志（logcat tag=dsh-pet） */
    @JavascriptInterface
    fun log(msg: String) = android.util.Log.i("dsh-pet", msg)

    /** 轻触觉反馈（长按菜单打开时） */
    @JavascriptInterface
    fun haptic() = svc.hapticFeedback()

    /** 打开原生设置页 */
    @JavascriptInterface
    fun openSettings() = svc.openSettingsActivity()
    /**
     * 菜单打开状态上报（需求①：菜单外点击逐级收起）。
     * 菜单打开期间，Kotlin 维护全屏捕获层；菜单外点击会被换算成合成 mousedown
     * 注入 WebView，触发 JS 侧 mh() 的 document mousedown 处理器逐级收起。
     */
    @JavascriptInterface
    fun setMenuOpen(open: Boolean) = svc.setMenuOpen(open)
    /** 输入框聚焦 / 失焦：切换渲染窗口的可聚焦性并唤起或收起输入法 */
    @JavascriptInterface
    fun setFocusMode(on: Boolean) = svc.setFocusMode(on)
    /**
     * 穿透模式开关：true = 所有触摸层收回 1×1（屏幕整块穿透给下层 App），
     * false = 恢复正常触摸层跟随。
     * 角落小按钮（48×48）始终可点，用于退出穿透。
     */
    @JavascriptInterface
    fun setClickThrough(on: Boolean) = svc.setClickThrough(on)
/** 吃白饭运行状态上报（JS 开演/收场边界调用）：通知栏按钮标签据此动态切换「吃白饭↔停止吃白饭」。 */
    @JavascriptInterface
    fun setBaifanRunning(on: Boolean) = svc.setBaifanRunning(on)

    /**
     * 提醒气泡点击 → 跳设置页提醒 tab 并定位到该条（focusId 可为空 = 只进提醒 tab）。
     * 由 reminder.js 的 __dshReminderBubble 气泡点击调用。
     */
    @JavascriptInterface
    fun openReminders(focusId: String?) = svc.openRemindersPage(focusId)

    /**
     * 提醒气泡命中区上报（JS 归一化矩形）：Kotlin 铺一个**专用**触摸层窗口（不复用 dsh-pet.js
     * 的 8 窗口池，避免被其每帧心跳 applyTouchLayersJson 盖掉），点按跳设置页提醒 tab 定位该条。
     * json = {x,y,w,h,id} 或 {active:false}（收回）。
     */
    @JavascriptInterface
    fun reminderBubbleRect(json: String) = svc.applyReminderBubbleRect(json)

    companion object {
        /**
         * 内置默认台词库（桌面宠物口吻，轻松日常）。
         * 用户没填自定义台词库时兜底用；开了「组合」开关时与自定义合并去重。
         */
        val defaultSayings: List<String> = listOf(
            "主人，欢迎回来～",
            "今天也要好好休息哦",
            "需要我帮忙吗？",
            "我在这儿呢，一直都在",
            "有点想你了，才不是想偷懒",
            "摸摸我的头嘛",
            "窗外的云像在慢慢散步呢",
            "别一直盯着屏幕啦，眨眨眼",
            "我记得今天，你对我很好",
            "要不要一起发会儿呆",
            "我肚子咕噜了一声，大概没有",
            "时间过得好快，你还好吗",
            "悄悄说：你笑起来很好看",
            "今天的我，也在努力营业中",
        )
    }
}