package com.ryan.robbaniexam.data.repository

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ryan.robbaniexam.data.model.ExamSession
import com.ryan.robbaniexam.data.model.LiveStudentStatus
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object ExamRepository {
    private val gson = Gson()
    private val cachedSessions = mutableMapOf<String, ExamSession>()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    // Standardized Firebase Realtime Database URL
    var firebaseDbUrl: String = "https://robbaniexam-default-rtdb.firebaseio.com"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .build()

    private val sseClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    data class RemoteCommandUpdate(
        val remoteCommand: String = "",
        val remoteMessage: String = "",
        val status: String? = null,
        val violationCount: Int? = null
    )

    class StreamHandle {
        private val cancelled = AtomicBoolean(false)
        @Volatile
        var currentCall: Call? = null
        @Volatile
        var workerThread: Thread? = null

        val isCancelled: Boolean get() = cancelled.get()

        fun stop() {
            cancelled.set(true)
            try {
                currentCall?.cancel()
            } catch (_: Exception) {
            }
            try {
                workerThread?.interrupt()
            } catch (_: Exception) {
            }
        }
    }

    private fun normalizedBaseUrl(): String {
        return if (firebaseDbUrl.endsWith("/")) firebaseDbUrl.dropLast(1) else firebaseDbUrl
    }

    fun sanitizeId(rawId: String): String {
        return rawId.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifEmpty { "STU_UNKNOWN" }
    }

    fun registerSession(session: ExamSession) {
        if (session.examCode.isNotEmpty()) {
            cachedSessions[session.examCode.uppercase()] = session
        }
    }

    fun clearCache() {
        cachedSessions.clear()
    }

    fun parseExamSessionFromQr(qrContent: String): ExamSession? {
        val trimmed = qrContent.trim()
        if (trimmed.isEmpty()) return null

        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            val session = ExamSession(
                examCode = "QR-" + System.currentTimeMillis().toString().takeLast(4),
                title = "Ujian Moodle LMS Sekolah",
                moodleUrl = trimmed,
                durationMinutes = 90,
                adminPin = "123456"
            )
            registerSession(session)
            return session
        }

        return try {
            val session = gson.fromJson(trimmed, ExamSession::class.java)
            if (session != null && session.moodleUrl.isNotEmpty()) {
                registerSession(session)
                session
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun getExamSessionByInput(input: String): ExamSession {
        val cleanInput = input.trim()

        if (cleanInput.startsWith("http://", ignoreCase = true) || cleanInput.startsWith("https://", ignoreCase = true)) {
            val session = ExamSession(
                examCode = "LINK-" + System.currentTimeMillis().toString().takeLast(4),
                title = "Ujian Moodle LMS Sekolah",
                moodleUrl = cleanInput,
                durationMinutes = 90,
                adminPin = "123456"
            )
            registerSession(session)
            return session
        }

        val cached = cachedSessions[cleanInput.uppercase()]
        if (cached != null) {
            return cached
        }

        val formattedUrl = if (cleanInput.contains(".")) {
            if (!cleanInput.startsWith("http")) "https://$cleanInput" else cleanInput
        } else {
            "https://elearning.robbani.sch.id"
        }

        return ExamSession(
            examCode = cleanInput.uppercase(),
            title = "Ujian Moodle ($cleanInput)",
            moodleUrl = formattedUrl,
            durationMinutes = 90,
            adminPin = "123456"
        )
    }

    fun resolveExamSessionAsync(
        input: String,
        onResult: (session: ExamSession, fromFirebase: Boolean) -> Unit
    ) {
        val cleanInput = input.trim()
        if (cleanInput.startsWith("http://", ignoreCase = true) ||
            cleanInput.startsWith("https://", ignoreCase = true) ||
            cleanInput.contains(".")
        ) {
            onResult(getExamSessionByInput(cleanInput), false)
            return
        }

        val upperCode = cleanInput.uppercase()
        val cached = cachedSessions[upperCode]
        if (cached != null && cached.moodleUrl.isNotEmpty()) {
            onResult(cached, true)
            return
        }

        Thread {
            var resolved: ExamSession? = null
            try {
                val targetUrl = "${normalizedBaseUrl()}/exams/${sanitizeId(upperCode)}.json"
                val request = Request.Builder().url(targetUrl).get().build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrBlank() && body != "null") {
                            val parsed = gson.fromJson(body, ExamSession::class.java)
                            if (parsed != null && parsed.moodleUrl.isNotEmpty()) {
                                registerSession(parsed)
                                resolved = parsed
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }

            mainHandler.post {
                if (resolved != null) {
                    onResult(resolved!!, true)
                } else {
                    onResult(getExamSessionByInput(cleanInput), false)
                }
            }
        }.start()
    }

    fun fetchBroadcastAnnouncement(onResult: (String?) -> Unit) {
        Thread {
            var message: String? = null
            try {
                val targetUrl = "${normalizedBaseUrl()}/settings/announcement.json"
                val request = Request.Builder().url(targetUrl).get().build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()?.trim()
                        if (!body.isNullOrBlank() && body != "null") {
                            val elem = JsonParser.parseString(body)
                            if (elem.isJsonObject) {
                                val text = elem.asJsonObject.get("message")?.takeIf { !it.isJsonNull }?.asString?.trim()
                                if (!text.isNullOrEmpty()) message = text
                            } else if (elem.isJsonPrimitive) {
                                val text = elem.asString.trim()
                                if (text.isNotEmpty()) message = text
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
            mainHandler.post { onResult(message) }
        }.start()
    }

    /**
     * Fetches dynamic class list managed by Admin in Firebase /classes.json.
     */
    fun fetchAvailableClasses(onResult: (List<String>) -> Unit) {
        Thread {
            val classes = mutableListOf<String>()
            try {
                val targetUrl = "${normalizedBaseUrl()}/classes.json"
                val request = Request.Builder().url(targetUrl).get().build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()?.trim()
                        if (!body.isNullOrBlank() && body != "null") {
                            val root = JsonParser.parseString(body)
                            if (root.isJsonObject) {
                                root.asJsonObject.entrySet().forEach { entry ->
                                    val v = entry.value
                                    val name = if (v.isJsonObject) {
                                        v.asJsonObject.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim()
                                    } else if (v.isJsonPrimitive) {
                                        v.asString.trim()
                                    } else null
                                    if (!name.isNullOrEmpty()) classes.add(name)
                                }
                            } else if (root.isJsonArray) {
                                root.asJsonArray.forEach { item ->
                                    val name = if (item.isJsonObject) {
                                        item.asJsonObject.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim()
                                    } else if (item.isJsonPrimitive) {
                                        item.asString.trim()
                                    } else null
                                    if (!name.isNullOrEmpty()) classes.add(name)
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
            mainHandler.post { onResult(classes.distinct().sorted()) }
        }.start()
    }

    /**
     * Sends student status & violations via PATCH so heartbeats never overwrite pending remoteCommand from dashboard.
     */
    fun pushLiveStudentStatus(status: LiveStudentStatus, onComplete: ((Boolean) -> Unit)? = null) {
        Thread {
            var success = false
            try {
                val obj = gson.toJsonTree(status).asJsonObject
                obj.remove("remoteCommand")
                obj.remove("remoteMessage")
                val body = obj.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val cleanStudentId = sanitizeId(status.studentId)
                val targetUrl = "${normalizedBaseUrl()}/students/$cleanStudentId.json"

                val request = Request.Builder()
                    .url(targetUrl)
                    .patch(body)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    success = response.isSuccessful
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            if (onComplete != null) {
                mainHandler.post { onComplete(success) }
            }
        }.start()
    }

    /**
     * Parses a Firebase RTDB SSE "put" or "patch" event payload into RemoteCommandUpdate.
     */
    fun parseSseEventPayload(eventType: String, rawJson: String): RemoteCommandUpdate? {
        if (eventType != "put" && eventType != "patch") return null
        if (rawJson.isBlank() || rawJson == "null") return null
        return try {
            val root = JsonParser.parseString(rawJson).asJsonObject
            val path = root.get("path")?.asString ?: "/"
            val dataElem = root.get("data") ?: return null
            if (dataElem.isJsonNull) return null

            if (path == "/" && dataElem.isJsonObject) {
                val obj: JsonObject = dataElem.asJsonObject
                val cmd = obj.get("remoteCommand")?.takeIf { !it.isJsonNull }?.asString ?: ""
                val msg = obj.get("remoteMessage")?.takeIf { !it.isJsonNull }?.asString ?: ""
                val st = obj.get("status")?.takeIf { !it.isJsonNull }?.asString
                val vc = obj.get("violationCount")?.takeIf { !it.isJsonNull }?.asInt
                RemoteCommandUpdate(cmd, msg, st, vc)
            } else if (path == "/remoteCommand" && dataElem.isJsonPrimitive) {
                RemoteCommandUpdate(remoteCommand = dataElem.asString)
            } else if (path == "/remoteMessage" && dataElem.isJsonPrimitive) {
                RemoteCommandUpdate(remoteMessage = dataElem.asString)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Opens a persistent Firebase RTDB SSE stream on /students/{studentId}.json
     * so remote teacher commands (MESSAGE, UNLOCK, FORCE_FINISH) arrive in <500ms.
     */
    fun startStudentCommandStream(
        studentId: String,
        onUpdate: (RemoteCommandUpdate) -> Unit
    ): StreamHandle {
        val handle = StreamHandle()
        val cleanId = sanitizeId(studentId)
        val streamUrl = "${normalizedBaseUrl()}/students/$cleanId.json"

        val thread = Thread {
            while (!handle.isCancelled && !Thread.currentThread().isInterrupted) {
                try {
                    val request = Request.Builder()
                        .url(streamUrl)
                        .header("Accept", "text/event-stream")
                        .header("Accept-Encoding", "identity")
                        .header("Cache-Control", "no-cache")
                        .build()

                    val call = sseClient.newCall(request)
                    handle.currentCall = call

                    call.execute().use { response ->
                        val body = response.body
                        Log.d("RobbaniSSE", "Connected HTTP ${response.code} to $streamUrl")
                        if (response.isSuccessful && body != null) {
                            val source = body.source()
                            var currentEvent = ""
                            while (!handle.isCancelled && !source.exhausted()) {
                                val line = source.readUtf8Line() ?: break
                                when {
                                    line.startsWith("event:") -> {
                                        currentEvent = line.removePrefix("event:").trim()
                                    }
                                    line.startsWith("data:") -> {
                                        val data = line.removePrefix("data:").trim()
                                        val update = parseSseEventPayload(currentEvent, data)
                                        if (update != null && (update.remoteCommand.isNotEmpty() || update.status != null)) {
                                            Log.d("RobbaniSSE", "Event $currentEvent -> cmd=${update.remoteCommand}")
                                            mainHandler.post {
                                                if (!handle.isCancelled) {
                                                    onUpdate(update)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("RobbaniSSE", "Stream error: ${e.message}")
                }

                if (!handle.isCancelled) {
                    try {
                        Thread.sleep(1500L)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }
        handle.workerThread = thread
        thread.start()
        return handle
    }

    fun fetchStudentRemoteState(studentId: String, onResult: (LiveStudentStatus?) -> Unit) {
        Thread {
            var remoteState: LiveStudentStatus? = null
            try {
                val cleanStudentId = sanitizeId(studentId)
                val targetUrl = "${normalizedBaseUrl()}/students/$cleanStudentId.json"
                val request = Request.Builder().url(targetUrl).get().build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrBlank() && body != "null") {
                            remoteState = gson.fromJson(body, LiveStudentStatus::class.java)
                        }
                    }
                }
            } catch (_: Exception) {
            }
            mainHandler.post { onResult(remoteState) }
        }.start()
    }

    fun clearRemoteCommand(studentId: String) {
        Thread {
            try {
                val cleanStudentId = sanitizeId(studentId)
                val targetUrl = "${normalizedBaseUrl()}/students/$cleanStudentId.json"
                val payload = """{"remoteCommand":"","remoteMessage":""}"""
                val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder().url(targetUrl).patch(body).build()
                httpClient.newCall(request).execute().use { resp ->
                    Log.d("RobbaniSSE", "Cleared command HTTP ${resp.code}")
                }
            } catch (e: Exception) {
                Log.w("RobbaniSSE", "Clear command failed: ${e.message}")
            }
        }.start()
    }
}
