package com.dsh.pet

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 模型调用（OkHttp，OpenAI 兼容 /chat/completions）。
 *
 * 与 android/host.ts 的 callLlm 契约：JS 侧 native.llm(payloadJson) 立即拿到请求号 id，
 * 结果由本对象异步回调 window.__dshLlmResult(id, resultJson)。失败一律返回结构化原因
 * （ok:false + reason），**绝不伪造文本**——与源项目同一原则。
 *
 * payload = {kind:'whisper'|'chat', system, messages:[{role,content}], maxTokens}
 * 返回   = {ok:true, text} | {ok:false, reason, message}
 */
object LlmClient {
    private val seq = AtomicLong(0)
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
    private val pool = Executors.newFixedThreadPool(2)
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** 入队一次调用，返回请求号（异步回调） */
    fun enqueue(svc: PetService, payloadJson: String): String {
        val id = "llm-" + seq.incrementAndGet()
        pool.execute {
            val result = try {
                call(svc, payloadJson)
            } catch (e: Exception) {
                err("fetch-error", e.message ?: "网络异常")
            }
            svc.deliverLlmResult(id, result)
        }
        return id
    }

    private fun err(reason: String, message: String): String =
        JSONObject().put("ok", false).put("reason", reason).put("message", message).toString()

    private fun call(svc: PetService, payloadJson: String): String {
        val ai = try { JSONObject(Store.readAiSettings(svc)) } catch (e: Exception) { JSONObject() }
        if (!ai.optBoolean("enabled", false)) {
            return err("provider-missing", "未启用 AI（设置页填写接口与密钥）")
        }
        val baseUrl = ai.optString("baseUrl", "").trim().trimEnd('/')
        val model = ai.optString("model", "").trim()
        val apiKey = ai.optString("apiKey", "").trim()
        if (baseUrl.isEmpty() || model.isEmpty()) {
            return err("provider-missing", "接口地址或模型名称为空")
        }

        val payload = try { JSONObject(payloadJson) } catch (e: Exception) {
            return err("bad-request", "payload 不是合法 JSON")
        }
        val system = payload.optString("system", "")
        val maxTokens = payload.optInt("maxTokens", 1024)

        val messages = JSONArray()
        if (system.isNotEmpty()) {
            messages.put(JSONObject().put("role", "system").put("content", system))
        }
        val src = payload.optJSONArray("messages")
        if (src != null) {
            for (i in 0 until src.length()) {
                val m = src.optJSONObject(i) ?: continue
                val role = m.optString("role", "user")
                val content = m.optString("content", "")
                if (content.isNotEmpty()) {
                    messages.put(JSONObject().put("role", role).put("content", content))
                }
            }
        }

        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("max_tokens", maxTokens)
            .put("temperature", 0.8)
            .put("stream", false)
            .toString()

        val reqBuilder = Request.Builder()
            .url("$baseUrl/chat/completions")
            .post(body.toRequestBody(JSON))
        if (apiKey.isNotEmpty()) reqBuilder.header("Authorization", "Bearer $apiKey")

        client.newCall(reqBuilder.build()).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                return err("http-" + resp.code, text.take(300).ifEmpty { "HTTP ${resp.code}" })
            }
            val root = try { JSONObject(text) } catch (e: Exception) {
                return err("fetch-error", "模型响应不可解析")
            }
            val content = try {
                root.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content", "")
            } catch (e: Exception) {
                ""
            }
            if (content.isBlank()) return err("empty-reply", "模型返回空内容")
            return JSONObject().put("ok", true).put("text", content).toString()
        }
    }
}