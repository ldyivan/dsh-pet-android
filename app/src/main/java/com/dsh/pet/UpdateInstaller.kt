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
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * App 内下载安装：把 GitHub Release 的 APK 下载到应用私有目录，再用 FileProvider
 * 交给系统安装器。与 [openUrl]（浏览器下载）并列，是「更新方式」的第二条路。
 *
 * 三条硬约束（改之前先读）：
 *  1. **下载目标固定落在 `filesDir/updates/`**，FileProvider 的 `file_paths.xml` 也只
 *     暴露这一个子目录。别图省事写外部存储——共享目录要额外申请 MANAGE_EXTERNAL_STORAGE，
 *     权限爆炸且没必要。
 *  2. **安装 Intent 必须 `addFlags(FLAG_GRANT_READ_URI_PERMISSION)`**：给安装器临时授
 *     读权限，否则 Android 7+ 私有目录的 content URI 读不到，装不上。
 *  3. **部分 ROM 还要用户手动开「允许安装未知应用」**：`canInstallPackages()` 为 false 时
 *     先 `ACTION_MANAGE_UNKNOWN_APP_SOURCES` 把它送过去，回来再触发安装，别直接弹安装器。
 *
 * 进度/完成统一切回主线程回调，调用方不用自己 post。
 */
object UpdateInstaller {

    private const val PROVIDER_AUTH = "com.dsh.pet.updatefile"
    private const val SUB_DIR = "updates"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true) // GitHub 下载链接会 302 到对象存储
        .build()
    private val pool = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** 下载进度回调：percent 0~100；finished=true 时 percent=100 */
    fun interface Progress {
        fun onProgress(percent: Int, finished: Boolean)
    }

    /** 能否直接调用安装器。false 时 [install] 会先带用户去系统里开「允许安装未知应用」。 */
    fun canInstallPackages(ctx: Context): Boolean =
        ctx.packageManager.canRequestPackageInstalls()

    /**
     * 下载 [url] 并安装。
     *
     * @param onProgress 下载进度（主线程回调）
     * @param onDone 下载成功后返回可安装 File；下载/异常返回 null（已在主线程 Toast 由调用方处理）
     */
    fun downloadAndInstall(
        ctx: Context,
        url: String,
        onProgress: Progress,
        onDone: (File?) -> Unit,
    ) {
        pool.execute {
            var f: File? = null
            try {
                val dir = File(ctx.filesDir, SUB_DIR).apply { mkdirs() }
                f = File(dir, apkName(url))
                val req = Request.Builder().url(url).header("User-Agent", "dsh-pet-android").build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        main.post { onDone(null) }
                        return@execute
                    }
                    val total = resp.body?.contentLength()?.takeIf { it > 0 } ?: -1L
                    val stream = resp.body?.byteStream() ?: return@execute
                    var sent = 0L
                    FileOutputStream(f).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = stream.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            sent += n
                            if (total > 0) {
                                val pct = min(99, (sent * 100 / total).toInt())
                                main.post { onProgress.onProgress(pct, false) }
                            }
                        }
                    }
                }
                if (f.length() == 0L) {
                    main.post { onDone(null) }
                    return@execute
                }
                main.post {
                    onProgress.onProgress(100, true)
                    onDone(f)
                    startInstall(ctx, f)
                }
            } catch (e: IOException) {
                f?.delete()
                main.post { onDone(null) }
            } catch (e: Exception) {
                f?.delete()
                main.post { onDone(null) }
            }
        }
    }

    /** 仅安装（不下载）。已下载好文件时走这里。 */
    fun install(ctx: Context, apk: File) = startInstall(ctx, apk)

    private fun startInstall(ctx: Context, apk: File) {
        if (!canInstallPackages(ctx)) {
            // 先送用户去系统里开「允许安装未知应用」，回来后手动再点安装
            try {
                val i = Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${ctx.packageName}"),
                )
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
                return
            } catch (e: Exception) {
                // 个别 ROM 没有该页：退回应用详情页
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
            // 没有任何安装器接得住：提示手动装
            android.widget.Toast.makeText(
                ctx, "无法启动安装器，请手动安装下载的 APK", android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    /** 从 URL 末段取 apk 文件名；取不到给默认名，避免下载到 0 字节 */
    private fun apkName(url: String): String {
        val last = url.substringAfterLast('/').substringBefore('?')
        return if (last.endsWith(".apk", true)) last else "dsh-pet-update.apk"
    }
}
