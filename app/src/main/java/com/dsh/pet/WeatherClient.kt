package com.dsh.pet

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 天气与环境（Android 独立实现，不依赖上游 host 侧的 UAPI）。
 *
 * 数据源：
 *  - 定位：ip-api.com 免费接口（无需密钥），拿到城市名与经纬度；
 *  - 天气：open-meteo.com 免费接口（无需密钥），按经纬度取当前温度 + 天气码。
 *
 * 配置（存 ui-settings.json 的 flags，与其它偏好同一套存储）：
 *  - weatherEnabled：总开关，默认关（联网取数据，给用户明确选择权）；
 *  - weatherCity：城市覆盖，留空 = 按 IP 自动判断；
 *  - weatherShowBubble：取到后在宠物旁弹一个小气泡，默认开。
 */
object WeatherClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val pool = Executors.newFixedThreadPool(2)

    /** WMO 天气码 → (中文描述, 图标字符)。 */
    private fun describe(code: Int): Pair<String, String> = when (code) {
        0 -> "晴" to "☀"
        1 -> "晴间多云" to "ἲ4"
        2 -> "多云" to "⛅"
        3 -> "阴" to "☁"
        45, 48 -> "雾" to "ἲB"
        in 51..57 -> "毛毛雨" to "ἲ6"
        in 61..67 -> "雨" to "ἲ7"
        in 71..77 -> "雪" to "ἲ8"
        in 80..82 -> "阵雨" to "ἲ7"
        in 85..86 -> "阵雪" to "ἲ8"
        in 95..99 -> "雷雨" to "⛈"
        else -> "未知" to "ἲ1"
    }

    /** 结果：ok=true 时 city/tempText/desc/icon 有效。 */
    data class Result(
        val ok: Boolean,
        val city: String = "",
        val tempText: String = "",
        val desc: String = "",
        val icon: String = "",
        val reason: String = "",
    ) {
        fun line(): String = if (!ok) reason else "$city · $tempText $desc"
    }

    /** 同步抓取（必须在后台线程调用）。 */
    private fun fetchBlocking(ctx: Context): Result {
        val manualCity = Store.prefStr(ctx, "weatherCity", "").trim()
        val lat: Double
        val lon: Double
        val city: String
        try {
            if (manualCity.isNotEmpty()) {
                val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=" +
                    java.net.URLEncoder.encode(manualCity, "UTF-8") +
                    "&count=1&language=zh&format=json"
                val results = JSONObject(get(geoUrl)).optJSONArray("results")
                    ?: return Result(false, reason = "找不到城市「" + manualCity + "」")
                if (results.length() == 0) return Result(false, reason = "找不到城市「" + manualCity + "」")
                val g = results.getJSONObject(0)
                lat = g.getDouble("latitude")
                lon = g.getDouble("longitude")
                city = g.optString("name", manualCity)
            } else {
                val lo = JSONObject(get("http://ip-api.com/json/?lang=zh-CN&fields=status,message,city,lat,lon"))
                if (lo.optString("status") != "success") {
                    return Result(false, reason = "定位失败：" + lo.optString("message", "未知原因"))
                }
                lat = lo.getDouble("lat")
                lon = lo.getDouble("lon")
                city = lo.optString("city", "当前位置")
            }
        } catch (e: Exception) {
            return Result(false, reason = "获取位置失败：" + (e.message ?: "网络异常"))
        }

        return try {
            val wUrl = "https://api.open-meteo.com/v1/forecast?latitude=" + lat +
                "&longitude=" + lon + "&current=temperature_2m,weather_code"
            val w = JSONObject(get(wUrl)).optJSONObject("current")
                ?: return Result(false, reason = "天气数据为空")
            val temp = w.optDouble("temperature_2m", Double.NaN)
            val code = w.optInt("weather_code", -1)
            if (temp.isNaN()) return Result(false, reason = "天气数据解析失败")
            val (desc, icon) = describe(code)
            Result(ok = true, city = city, tempText = Math.round(temp).toString() + "°C", desc = desc, icon = icon)
        } catch (e: Exception) {
            Result(false, reason = "获取天气失败：" + (e.message ?: "网络异常"))
        }
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", "dsh-pet-android").build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw RuntimeException("HTTP " + resp.code)
            return body
        }
    }

    /** 异步查询并在结果就绪后主线程回调。开关关闭时直接回调，不发请求。 */
    fun query(ctx: Context, cb: (Result) -> Unit) {
        if (!Store.prefBool(ctx, "weatherEnabled", false)) {
            cb(Result(false, reason = "天气未启用"))
            return
        }
        pool.execute {
            val r = fetchBlocking(ctx)
            android.os.Handler(android.os.Looper.getMainLooper()).post { cb(r) }
        }
    }
}
