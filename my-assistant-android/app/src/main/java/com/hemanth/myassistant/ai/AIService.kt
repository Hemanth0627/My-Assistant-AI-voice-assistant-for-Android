package com.hemanth.myassistant.ai

import android.util.Log
import com.hemanth.myassistant.BuildConfig
import com.hemanth.myassistant.model.AssistantAction
import com.hemanth.myassistant.model.ChatMessage
import com.hemanth.myassistant.model.Sender
import com.hemanth.myassistant.model.WebSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

private const val TAG = "AssistantAI"

// Set in local.properties (backend.url / backend.token) and copied here by Gradle at build time.
private val BACKEND_URL = BuildConfig.BACKEND_URL.trimEnd('/')
private val APP_TOKEN = BuildConfig.APP_TOKEN

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

// 7+ digit sequences (phone, card, account numbers), with optional + spaces or dashes.
private val LONG_NUMBER = Regex("\\+?\\d[\\d -]{5,}\\d")

sealed interface AIResult {
    /** The AI answered. [action] is a validated action (or null); [sources] are web pages used. */
    data class Success(
        val reply: String,
        val action: AssistantAction?,
        val sources: List<WebSource> = emptyList()
    ) : AIResult

    /** Something failed. [userMessage] is safe to show to the user. */
    data class Failure(val userMessage: String) : AIResult
}

class AIService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        // Never silently RESEND a chat request: one voice command must mean one AI request.
        .retryOnConnectionFailure(false)
        .build()

    /** Wakes the server early so the first real question is faster. Failures are ignored. */
    suspend fun warmUp() {
        withContext(Dispatchers.IO) {
            try {
                client.newCall(Request.Builder().url("$BACKEND_URL/health").build())
                    .execute()
                    .close()
                Log.d(TAG, "Server is awake")
            } catch (e: Exception) {
                Log.d(TAG, "Warm-up failed (offline or server unreachable)")
            }
        }
    }

    suspend fun chat(message: String, history: List<ChatMessage>, requestId: String): AIResult =
        withContext(Dispatchers.IO) {
            val shortId = requestId.take(8)
            try {
                val body = buildRequestJson(message, history)
                val request = Request.Builder()
                    .url("$BACKEND_URL/chat")
                    .header("X-App-Token", APP_TOKEN)
                    .header("X-Request-Id", requestId)
                    .post(body.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                Log.d(TAG, "Request $shortId sent")
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()

                    if (!response.isSuccessful) {
                        Log.w(TAG, "Request $shortId failed: HTTP ${response.code}, body: ${text.take(200)}")
                        return@withContext AIResult.Failure(errorFor(response.code, text))
                    }

                    val json = JSONObject(text)
                    val reply = json.optString("reply").trim()
                    val action = ActionParser.parse(json.optJSONObject("action"))
                    val sources = parseSources(json.optJSONArray("sources"))
                    Log.d(TAG, "Request $shortId OK")

                    if (reply.isEmpty() && action == null) {
                        AIResult.Failure("I wasn't able to do that. Please try rephrasing.")
                    } else {
                        AIResult.Success(reply, action, sources)
                    }
                }
            } catch (e: UnknownHostException) {
                Log.w(TAG, "Request $shortId: no internet / DNS failure")
                AIResult.Failure(
                    "No internet connection. I can still open apps, check the time and battery, " +
                            "and start calls."
                )
            } catch (e: ConnectException) {
                Log.w(TAG, "Request $shortId: connection refused")
                AIResult.Failure("Can't reach the AI server right now. Please try again.")
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "Request $shortId: timed out")
                AIResult.Failure("The AI took too long to answer. If it was asleep, try once more.")
            } catch (e: IOException) {
                Log.w(TAG, "Request $shortId: network error", e)
                AIResult.Failure("Network problem while contacting the AI. Please try again.")
            } catch (e: JSONException) {
                Log.w(TAG, "Request $shortId: bad JSON from backend", e)
                AIResult.Failure("The AI server sent an unexpected response.")
            } catch (e: Exception) {
                // Anything unexpected: report it instead of leaving the app stuck on "Thinking".
                Log.e(TAG, "Request $shortId: unexpected error", e)
                AIResult.Failure("Something went wrong. Please try again.")
            }
        }

    private fun buildRequestJson(message: String, history: List<ChatMessage>): String {
        val historyJson = JSONArray()
        history.forEach { msg ->
            historyJson.put(
                JSONObject()
                    .put("role", if (msg.sender == Sender.USER) "user" else "assistant")
                    .put("content", msg.text.replace(LONG_NUMBER, "[number removed]"))
            )
        }
        return JSONObject()
            .put("message", message)
            .put("history", historyJson)
            .toString()
    }

    /** Reads the sources list. Only secure https:// links are accepted, at most 3. */
    private fun parseSources(array: JSONArray?): List<WebSource> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val url = item.optString("url")
            if (!url.startsWith("https://")) return@mapNotNull null
            WebSource(title = item.optString("title").ifBlank { url }.take(120), url = url)
        }.take(3)
    }

    private fun errorFor(code: Int, body: String): String {
        val detail = try {
            JSONObject(body).optString("detail")
        } catch (e: JSONException) {
            ""
        }
        return when {
            code == 401 -> "The app isn't authorized with the server. Check the app token."
            detail.isNotBlank() && !detail.startsWith("[") -> detail
            code == 429 -> "The AI is busy right now. Try again in a moment."
            else -> "The AI server returned an error ($code)."
        }
    }
}