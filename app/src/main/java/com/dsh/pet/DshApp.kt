package com.dsh.pet

import android.app.Application
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 全局崩溃捕获：把未处理异常的完整堆栈追加到 [CRASH_LOG_NAME]（多路冗余写入）。
 *
 * 闭环：崩溃落盘 → 下次进设置页自动展示 → 复制(即清空) / 手动清空，为下一次留干净记录。
 */
class DshApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            try {
                val ts = System.currentTimeMillis()
                val sw = StringWriter()
                ex.printStackTrace(PrintWriter(sw))
                val body =
                    "\n===== CRASH @" + ts + " =====\n" +
                            "THREAD: " + thread.name + "\n" +
                            sw.toString()
                writeCrashLog(body)
                Log.e("dsh-pet", "捕获未处理异常已写日志: " + ex.javaClass.simpleName + " - " + ex.message)
            } catch (ignored: Exception) {
            }
            // 交回默认处理器，系统照常处理崩溃
            if (default != null) default.uncaughtException(thread, ex)
        }
    }

    /** 供 PetService 的非崩溃诊断（通知降级等）复用同一份日志文件。 */
    fun writeCrashLog(body: String) {
        // ① 应用私有外部目录（无权限、可写、且 Shizuku shell 可直接读）——主落点
        try {
            val ext = File(getExternalFilesDir(null), CRASH_LOG_NAME)
            ext.parentFile?.mkdirs()
            FileWriter(ext, true).use { w -> w.write(body) }
        } catch (e: Exception) {
            Log.w("dsh-pet", "写外部崩溃日志失败: " + e.message)
        }
        // ② 内部 filesDir 兑底
        try {
            val f = File(filesDir, CRASH_LOG_NAME)
            FileWriter(f, true).use { w -> w.write(body) }
        } catch (ignored: Exception) {
        }
        // ③ /sdcard 根（targetSdk35 大概率写不进，尽力而为）
        try {
            FileWriter(File("/sdcard/" + CRASH_LOG_NAME), true).use { w -> w.write(body) }
        } catch (ignored: Exception) {
        }
    }

    /** 清空全部崩溃日志（复制后即调，为下一次留干净记录）。 */
    fun clearCrashLog() {
        try { FileWriter(File(getExternalFilesDir(null), CRASH_LOG_NAME), false).use { } } catch (ignored: Exception) { }
        try { FileWriter(File(filesDir, CRASH_LOG_NAME), false).use { } } catch (ignored: Exception) { }
        try { File("/sdcard/" + CRASH_LOG_NAME).delete() } catch (ignored: Exception) { }
    }

    companion object {
        const val CRASH_LOG_NAME = "dshcrash.log"
    }
}
