package org.example.code_visual_plugin.ai

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * AI 配置。存在 codeCanvasAi.xml 里（API Key 不在此处，见 AiKeyStore）。
 *
 * 只实现 OpenAI 兼容协议（/chat/completions），一份代码覆盖：
 *   DeepSeek / 通义千问 / Kimi / 智谱 / 硅基流动 / Ollama / LM Studio / vLLM …
 * 用户只需要填 Base URL + Model。
 */
@State(name = "CodeCanvasAiSettings", storages = [Storage("codeCanvasAi.xml")])
class AiSettings : PersistentStateComponent<AiSettings.SettingsState> {

    data class SettingsState(
        var enabled: Boolean = true,
        var baseUrl: String = "https://api.deepseek.com/v1",
        var model: String = "deepseek-chat",
        var temperature: Double = 0.2,
        var maxTokens: Int = 4096
    )

    private var myState = SettingsState()

    override fun getState(): SettingsState = myState

    override fun loadState(state: SettingsState) {
        myState = state
    }

    companion object {
        fun getInstance(): AiSettings = service()
    }
}

/**
 * API Key 存 IDE 的系统凭据库（Windows 凭据管理器 / macOS Keychain）。
 *
 * 注意：PasswordSafe 的读写是「慢操作」，**不能在 EDT 上直接调**，
 * 否则 IDEA 会抛 "Slow operations are prohibited on EDT"。
 * 所以这里做了一层内存缓存：
 *   - 后台线程可以走 load() 同步读
 *   - EDT（设置页 isModified / createComponent）只能读 cached()
 *   - 异步加载完成后再回填 UI
 */
object AiKeyStore {

    private val ATTRS = CredentialAttributes("CodeCanvasAI/apiKey")

    @Volatile
    private var cached: String? = null

    @Volatile
    private var loadedOnce = false

    private fun onEdt(): Boolean = ApplicationManager.getApplication().isDispatchThread

    fun cached(): String? = cached

    fun load(): String? = if (loadedOnce) cached else safeRead()

    fun isConfigured(): Boolean {
        val value = when {
            loadedOnce -> cached
            onEdt() -> null          // EDT 上不读凭据库
            else -> safeRead()
        }
        return !value.isNullOrBlank()
    }

    private fun safeRead(): String? {
        return try {
            val value = PasswordSafe.instance.getPassword(ATTRS)
            cached = value
            loadedOnce = true
            value
        } catch (e: Throwable) {
            loadedOnce = true
            null
        }
    }

    /** 后台读一次，回到 EDT 回调。设置页用它填充输入框。 */
    fun refreshAsync(onDone: (String?) -> Unit = {}) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val value = safeRead()
            ApplicationManager.getApplication().invokeLater { onDone(value) }
        }
    }

    fun save(key: String?) {
        val normalized = key?.takeIf { it.isNotBlank() }
        cached = normalized
        loadedOnce = true
        // 写凭据库同样不能在 EDT，扔到后台
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                PasswordSafe.instance.setPassword(ATTRS, normalized)
            } catch (e: Throwable) {
                // 某些环境下没有可用的凭据库，静默失败；本次会话内仍可用 cached 值
            }
        }
    }
}

/** 调 AI 时的可读异常，直接透传给前端显示 */
class AiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

object AiClient {

    private val log = Logger.getInstance(AiClient::class.java)
    private val gson = Gson()

    fun isReady(): Boolean = AiSettings.getInstance().state.enabled && AiKeyStore.isConfigured()

    /**
     * 发一轮对话，返回模型输出的原始文本。
     * 非 200 会把状态码 + 响应体前 400 字带出来，便于定位是 key 错、模型名错还是限流。
     */
    fun chat(system: String, user: String): String {
        val settings = AiSettings.getInstance().state
        val key = AiKeyStore.load()
            ?: throw AiException("还没有配置 API Key。请到 Settings → Tools → CodeCanvas AI 里填写。")

        val base = settings.baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) throw AiException("Base URL 为空，请在设置里填写（例如 https://api.deepseek.com/v1）")

        val url = "$base/chat/completions"

        val payload = JsonObject().apply {
            addProperty("model", settings.model)
            addProperty("temperature", settings.temperature)
            addProperty("max_tokens", settings.maxTokens)
            addProperty("stream", false)
            add("messages", gson.toJsonTree(listOf(
                mapOf("role" to "system", "content" to system),
                mapOf("role" to "user", "content" to user)
            )))
        }

        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

        fun send(body: String): HttpResponse<String> {
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "Bearer $key")
                .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
                .build()
            return client.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        }

        val body = gson.toJson(payload)
        log.info("CodeCanvas AI request → $url (model=${settings.model})")

        val response = try {
            send(body)
        } catch (e: Throwable) {
            throw AiException("请求 AI 服务失败：${e.javaClass.simpleName}: ${e.message}", e)
        }

        if (response.statusCode() !in 200..299) {
            val snippet = response.body().take(400).replace("\n", " ")
            throw AiException("AI 服务返回 HTTP ${response.statusCode()}：$snippet")
        }

        val parsed = try {
            JsonParser.parseString(response.body()).asJsonObject
        } catch (e: Throwable) {
            throw AiException("AI 返回的不是合法 JSON：${response.body().take(300)}", e)
        }

        val choices = parsed.getAsJsonArray("choices")
            ?: throw AiException("AI 响应里没有 choices 字段：${response.body().take(300)}")

        if (choices.size() == 0) throw AiException("AI 返回了空的 choices")

        val message = choices[0].asJsonObject.getAsJsonObject("message")
            ?: throw AiException("AI 响应里没有 message 字段")

        val content = message.get("content")?.asString
            ?: throw AiException("AI 响应里没有 content 字段")

        return content
    }

    /** 让用户在设置页点一下就能验证配置是否可用 */
    fun ping(): String {
        return chat(
            "你是一个连通性测试助手，只回复四个字：连接成功",
            "请回复：连接成功"
        ).trim()
    }
}

