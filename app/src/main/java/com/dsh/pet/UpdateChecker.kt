package com.dsh.pet

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * GitHub Releases 更新检查。
 *
 * 改这里之前先读四条约束：
 *  1. **只读公开 API，不带任何 token**。应用本身是开源的，客户端里塞凭据等于公开泄露；
 *     未认证请求限额 60 次/小时/IP，够用（只在进设置页静默一次 + 用户手点）。
 *  2. **失败必须说清原因**：无网络 / 404 还没发布 / 403 限流，三种话术不一样，
 *     不能只让按钮转圈然后什么都不说。
 *  3. **版本比较走数字段**：字符串比 `"1.0.10" > "1.0.9"` 会得出相反结论。
 *  4. 回调统一切回主线程，调用方不用自己 post。
 */
object UpdateChecker {

    /**
     * 预设更新源（同时也是「开源许可」里本项目的链接）。
     *
     * fork 之后把这两行改成自己的账号/仓库名即可，别的地方都从这里取值。
     */
    const val REPO_OWNER = "PC2005-cloud"
    const val REPO_NAME = "dsh-pet-android"

    val repoUrl: String get() = "https://github.com/$REPO_OWNER/$REPO_NAME"
    val releasesUrl: String get() = "$repoUrl/releases"

    private val latestApi: String
        get() = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"

    /** 一次检查的结果；`newer=false` 表示已经是最新 */
    data class Result(
        val tag: String,
        val title: String,
        val pageUrl: String,
        val notes: String,
        val apkUrl: String?,
        val newer: Boolean,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val pool = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** 当前安装的 versionName，读不到时给 "0"（让任何发布都算更新） */
    fun currentVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
    } catch (e: Exception) {
        "0"
    }

    /**
     * 异步检查一次。
     *
     * @param cb 成功时 `(result, null)`；失败时 `(null, 可直接展示的中文原因)`
     */
    fun check(ctx: Context, cb: (Result?, String?) -> Unit) {
        val current = currentVersion(ctx)
        pool.execute {
            var res: Result? = null
            var err: String? = null
            try {
                val req = Request.Builder()
                    .url(latestApi)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "dsh-pet-android/$current")
                    .build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    when {
                        resp.code == 404 ->
                            err = "这个仓库还没有发布任何版本（或更新源未配置）"
                        resp.code == 403 || resp.code == 429 ->
                            err = "GitHub 接口限流了，稍后再试，或直接打开发布页看"
                        !resp.isSuccessful ->
                            err = "GitHub 返回 ${resp.code}，稍后再试"
                        else -> {
                            val j = JSONObject(body)
                            val tag = j.optString("tag_name", "").ifEmpty { j.optString("name", "") }
                            val title = j.optString("name", "").ifEmpty { tag }
                            var apk: String? = null
                            val assets = j.optJSONArray("assets")
                            if (assets != null) {
                                for (i in 0 until assets.length()) {
                                    val a = assets.optJSONObject(i) ?: continue
                                    if (a.optString("name", "").endsWith(".apk", true)) {
                                        apk = a.optString("browser_download_url", "").ifEmpty { null }
                                        break
                                    }
                                }
                            }
                            res = Result(
                                tag = tag,
                                title = title,
                                pageUrl = j.optString("html_url", "").ifEmpty { releasesUrl },
                                notes = j.optString("body", ""),
                                apkUrl = apk,
                                newer = isNewer(tag, current),
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                err = "网络异常：" + (e.message ?: "无法连接 GitHub")
            }
            main.post { cb(res, err) }
        }
    }

    /**
     * `v1.2.10` 比 `1.2.9` 新；段数不同按 0 补齐；`1.2.0-beta` 当 `1.2.0` 比。
     * 纯数字比较，不做字符串比较。
     */
    fun isNewer(latest: String, current: String): Boolean {
        val a = numbers(latest)
        val b = numbers(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun numbers(v: String): List<Int> =
        v.trim()
            .removePrefix("v")
            .removePrefix("V")
            .split('.', '-', '+', '_', ' ')
            .mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
}