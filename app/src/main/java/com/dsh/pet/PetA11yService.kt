package com.dsh.pet

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 「完全体模式」所需的空壳无障碍服务。
 *
 * 为什么需要它：Android 12 起 InputDispatcher 会屏蔽「不可信触摸」——若触摸点上方压着
 * **另一个应用的窗口**、且该窗口 opacity 超过 Settings.Global 的
 * `maximum_obscuring_opacity_for_touch`，这次触摸直接被丢弃。桌宠的渲染窗口是全屏的，
 * 因此 alpha 必须压在阈值以下（约 78%），宠物就永远到不了 100% 不透明。
 *
 * 唯一的正规出口是让窗口成为**可信覆盖层**：com.android.server.wm.WindowState 会把
 * `InputMonitor.isTrustedOverlay(type)` 为真的窗口标记成 trustedOverlay，而该白名单里
 * 包含 TYPE_ACCESSIBILITY_OVERLAY。也就是说，用无障碍服务作为 Context 添加的覆盖层窗口
 * 不参与遮挡判定——这正是悬浮球 / 输入法「完全不透明又不挡桌面」的机制。
 *
 * 本服务不读屏、不注入事件、不申请任何能力（eventTypes 只留窗口状态，
 * canRetrieveWindowContent=false），唯一职责是提供一个 Context，
 * 让 PetService 能把渲染窗口挂成无障碍覆盖层。
 */
class PetA11yService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i("dsh-pet", "无障碍服务已连接：完全体模式可用（100% 不透明）")
        // 已经在跑的桌宠窗口还是旧类型，类型 addView 之后改不了 —— 让服务重建一次
        PetService.instance?.requestRebuild()
    }

    /** 不读屏：不需要任何事件 */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.i("dsh-pet", "无障碍服务已断开：回退普通悬浮窗（透明度回到系统上限）")
        PetService.instance?.requestRebuild()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        /** 当前已连接的服务实例；为 null 表示无障碍未开启 */
        @Volatile
        var instance: PetA11yService? = null
            private set

        /** 无障碍是否已开启（完全体模式的前置条件） */
        fun isEnabled(): Boolean = instance != null
    }
}
