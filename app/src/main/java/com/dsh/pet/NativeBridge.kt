package com.dsh.pet

import android.content.Context
import android.webkit.JavascriptInterface

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
     */
    @JavascriptInterface
    fun readUiSettings(): String = Store.readUiSettings(svc)

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

    /** 输入框聚焦 / 失焦：切换渲染窗口的可聚焦性并唤起或收起输入法 */
    @JavascriptInterface
    fun setFocusMode(on: Boolean) = svc.setFocusMode(on)
}