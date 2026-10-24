package com.dsh.pet

import android.content.Context
import java.io.File

/**
 * 应用私有目录持久化：用户层配置 / 对话记忆 / AI 设置。
 *
 * 口径与源项目 host 侧一致：
 * - 内置配置 = assets/config.jsonc（只读）；
 * - 用户层 = filesDir/main-config.jsonc（顶层整段替换，合并由 JS host 完成）；
 * - 对话记忆 = filesDir/memory.json（全存不删）；
 * - AI 设置 = filesDir/ai-settings.json（OpenAI 兼容参数）。
 *
 * 原子写：先写 .tmp 再 rename，避免写一半掉电留下半个 JSON。
 */
object Store {
    private fun f(ctx: Context, name: String) = File(ctx.filesDir, name)

    fun readUserConfig(ctx: Context): String = readText(f(ctx, "main-config.jsonc"))
    fun writeUserConfig(ctx: Context, json: String): Boolean =
        writeAtomic(f(ctx, "main-config.jsonc"), json)

    fun readMemory(ctx: Context): String = readText(f(ctx, "memory.json"))
    fun writeMemory(ctx: Context, json: String): Boolean = writeAtomic(f(ctx, "memory.json"), json)

    fun readAiSettings(ctx: Context): String = readText(f(ctx, "ai-settings.json")).ifEmpty { "{}" }
    fun writeAiSettings(ctx: Context, json: String): Boolean = writeAtomic(f(ctx, "ai-settings.json"), json)

    /**
     * UI 偏好（显示大小 / 透明度 / 点击音效 / 宠物数量 / 台词库）。
     *
     * 单独一份文件、不塞进 main-config.jsonc：那份是**宠物条目**配置，写坏一次宠物直接消失；
     * UI 偏好是「好看不好看」层面的东西，坏了大不了回默认值，不该连坐。
     */
    fun readUiSettings(ctx: Context): String = readText(f(ctx, "ui-settings.json")).ifEmpty { "{}" }
    fun writeUiSettings(ctx: Context, json: String): Boolean = writeAtomic(f(ctx, "ui-settings.json"), json)

    private fun readText(f: File): String = try {
        if (f.exists() && f.isFile) f.readText(Charsets.UTF_8) else ""
    } catch (e: Exception) {
        ""
    }

    /**
     * 原子写：先写 .tmp，再改名覆盖。
     *
     * 老实现是「先 delete 再 renameTo」——那是**两步**：删完如果 rename 失败（目标被占用、
     * 部分 ROM 的 rename 语义差异），文件就真没了，用户看到的就是「设置保存不了 / 保存后丢失」。
     * 现在先直接 renameTo（POSIX 下对已存在目标是原子覆盖），失败才退化成删旧+改名，
     * 并且把结果如实返回给调用方。
     */
    private fun writeAtomic(f: File, content: String): Boolean = try {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(content, Charsets.UTF_8)
        if (tmp.renameTo(f)) {
            true
        } else {
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        }
    } catch (e: Exception) {
        false
    }

    /** 读内置配置（assets/config.jsonc） */
    fun readBuiltinConfig(ctx: Context): String = try {
        ctx.assets.open("config.jsonc").bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (e: Exception) {
        ""
    }
}