package com.dsh.pet

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * App 内下载安装：把 GitHub Release 的 APK 下载到应用私有目录，再用 FileProvider
 * 交给系统安装器。与 [openUrl]（浏览器下载）并列，是「更新方式」的第二条路。
 *
 * v1.2.21 增强：
 *  · **断点续传**：下载中途断网/被杀，`<name>.part` 保留在盘上；下次自动带
 *    `Range: bytes=N-` 从 N 处续传（GitHub S3 支持 Range），不用从头来。
 *  · **已下载立即安装**：完整 APK 在盘上时跳过下载直接装，不再重复下载。
 *  · **权限分离**：下载完成但缺安装权限时，文件留在盘上，授权后回来直接装，
 *    不必重新下载。
 *
 * 三条硬约束（改之前先读）：
 *  1. **下载目标固定落在 `filesDir/updates/`**，FileProvider 的 `file_paths.xml` 也只
 *     暴露这一个子目录。别图省事写外部存储。
 *  2. **安装 Intent 必须 `addFlags(FLAG_GRANT_READ_URI_PERMISSION)`**：给安装器临时授
 *     读权限，否则 Android 7+ 私有目录的 content URI 读不到，装不上。
 *  3. **部分 ROM 还要用户手动开「允许安装未知应用」**：`canInstallPackages()` 为 false 时
 *     先送过去，回来后 [findCompleted] 仍能拿到文件，不必重下。
 */
object UpdateInstaller {
    private const val PROVIDER_AUTH = "com.dsh.pet.updatefile"
    private const val SUB_DIR = "updates"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    private val pool = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** 下载进度回调：percent 0~100；finished=true 时 percent=100 */
    fun interface Progress { fun onProgress(percent: Int, finished: Boolean) }

    /** 能否直接调用安装器。false 时 [install] 会先带用户去系统里开「允许安装未知应用」。 */
    fun canInstallPackages(ctx: Context): Boolean =
        ctx.packageManager.canRequestPackageInstalls()

    // ----------------------------------------------------------- 文件路径

    private fun finalFile(ctx: Context, url: String): File =
        File(File(ctx.filesDir, SUB_DIR), apkName(url))

    private fun partFile(ctx: Context, url: String): File =
        File(File(ctx.filesDir, SUB_DIR), apkName(url) + ".part")

    /** 查找已完整下载的 APK；找不到返回 null */
    fun findCompleted(ctx: Context, url: String): File? {
        val f = finalFile(ctx, url)
        return if (f.exists() && f.length() > 0) f else null
    }

    /** 查找正在下载（未完成）的部分文件及其已下载字节数 */
    fun findPartial(ctx: Context, url: String): Pair<File, Long>? {
        val f = partFile(ctx, url)
        return if (f.exists() && f.length() > 0) f to f.length() else null
    }

    /** 清除指定 URL 的下载缓存（完整 + 部分），用于"重新下载" */
    fun clearCache(ctx: Context, url: String) {
        finalFile(ctx, url).delete()
        partFile(ctx, url).delete()
    }

    // ----------------------------------------------------------- 下载 + 安装

    /**
     * 断点续传下载 [url] 并安装。
     *
     * 行为：
     *  · 已有完整 APK → 跳过下载，直接走安装
     *  · 有 `.part` 部分文件 → 带 Range 头续传
     *  · 没有 → 从头下
     *
     * @param onProgress 下载进度（主线程回调）
     * @param onDone 完成后回调（主线程）：
     *   - `file != null` → 文件在盘上（已完整或刚下完）
     *   - `msg != null`  → 出错原因（中文，可直接展示）
     */
    fun downloadAndInstall(
        ctx: Context,
        url: String,
        onProgress: Progress,
        onDone: (File?, String?) -> Unit,
    ) {
        pool.execute {
            val dir = File(ctx.filesDir, SUB_DIR).apply { mkdirs() }
            val f = finalFile(ctx, url)
            val part = partFile(ctx, url)

            // 1. 已完整 → 跳过下载直接装
            if (f.exists() && f.length() > 0) {
                main.post {
                    onProgress.onProgress(100, true)
                    onDone(f, null)
                    startInstall(ctx, f)
                }
                return@execute
            }

            // 2. 断点续传起点
            val startFrom = if (part.exists()) part.length() else 0L

            try {
                val builder = Request.Builder()
                    .url(url)
                    .header("User-Agent", "dsh-pet-android")
                if (startFrom > 0) builder.header("Range", "bytes=$startFrom-")

                client.newCall(builder.build()).execute().use { resp ->
                    val isResume = resp.code == 206
                    when {
                        isResume -> { /* 206 OK，续传 */ }
                        resp.code == 200 && startFrom > 0 -> {
                            // 服务器不支持 Range（返回 200 整包），清掉旧的 part 重下
                            part.delete()
                        }
                        !resp.isSuccessful -> {
                            main.post { onDone(null, "HTTP ${resp.code}") }
                            return@execute
                        }
                    }

                    val totalInResp = resp.body?.contentLength()?.takeIf { it > 0 } ?: -1L
                    val totalAll = if (isResume) startFrom + totalInResp else totalInResp
                    var sent = 0L

                    RandomAccessFile(part, "rw").use { raf ->
                        val seekTo = if (isResume) startFrom else 0L
                        raf.setLength(seekTo)
                        raf.seek(seekTo)
                        val stream = resp.body!!.byteStream()
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = stream.read(buf)
                            if (n < 0) break
                            raf.write(buf, 0, n)
                            sent += n
                            val done = if (isResume) startFrom + sent else sent
                            if (totalAll > 0) {
                                val pct = min(99, (done * 100 / totalAll).toInt())
                                main.post { onProgress.onProgress(pct, false) }
                            }
                        }
                    }

                    // 完成 → 把 .part 重命名为正式文件
                    part.copyTo(f, overwrite = true)
                    part.delete()

                    main.post {
                        onProgress.onProgress(100, true)
                        onDone(f, null)
                        startInstall(ctx, f)
                    }
                }
            } catch (e: IOException) {
                // 保留 .part（供下次续传），不删
                main.post { onDone(null, "网络中断（已保留 ${if (startFrom > 0) startFrom else 0} B，重进自动续传）") }
            } catch (e: Exception) {
                main.post { onDone(null, "下载失败：${e.message}") }
            }
        }
    }

    /** 仅安装（不下载）。已下载好文件时走这里。 */
    fun install(ctx: Context, apk: File) = startInstall(ctx, apk)

    // ----------------------------------------------------------- 安装

    private fun startInstall(ctx: Context, apk: File) {
        if (!canInstallPackages(ctx)) {
            // 先送用户去系统里开「允许安装未知应用」，回来后文件还在，再点一次即可
            try {
                val i = Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${ctx.packageName}"),
                )
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
                return
            } catch (e: Exception) {
                try {
                    val i = Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${ctx.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(i)
                } catch (ignored: Exception) { }
            }
            return
        }
        val uri: Uri = FileProvider.getUriForFile(ctx, PROVIDER_AUTH, apk)
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ctx.startActivity(i)
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                ctx, "无法启动安装器，请手动安装下载的 APK", android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    /** 从 URL 末段取 apk 文件名；取不到给默认名 */
    private fun apkName(url: String): String {
        val last = url.substringAfterLast('/').substringBefore('?')
        return if (last.endsWith(".apk", true)) last else "dsh-pet-update.apk"
    }
}