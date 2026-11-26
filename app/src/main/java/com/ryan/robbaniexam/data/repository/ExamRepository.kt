package com.ryan.robbaniexam.data.repository

import com.google.gson.Gson
import com.ryan.robbaniexam.data.model.ExamSession
import com.ryan.robbaniexam.data.model.LiveStudentStatus
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object ExamRepository {
    private val gson = Gson()
    private val cachedSessions = mutableMapOf<String, ExamSession>()

    // User's exact Firebase Realtime Database URL
    var firebaseDbUrl: String = "https://robbaniexam-default-rtdb.firebaseio.com"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun registerSession(session: ExamSession) {
        if (session.examCode.isNotEmpty()) {
            cachedSessions[session.examCode.uppercase()] = session
        }
    }

    fun parseExamSessionFromQr(qrJson: String): ExamSession? {
        return try {
            val session = gson.fromJson(qrJson, ExamSession::class.java)
            if (session != null && session.moodleUrl.isNotEmpty()) {
                registerSession(session)
                session
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun getExamSessionByInput(input: String): ExamSession {
        val cleanInput = input.trim()

        // 1. Direct URL check
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

        // 2. Check cached/registered sessions
        val cached = cachedSessions[cleanInput.uppercase()]
        if (cached != null) {
            return cached
        }

        // 3. Fallback: URL if contains dot, otherwise default URL
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

    /**
     * Sends student status & violations live to User's Firebase Realtime Database
     */
    fun pushLiveStudentStatus(status: LiveStudentStatus) {
        Thread {
            try {
                val jsonPayload = gson.toJson(status)
                val body = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType())

                val baseUrl = if (firebaseDbUrl.endsWith("/")) firebaseDbUrl.dropLast(1) else firebaseDbUrl
                val cleanStudentId = status.studentId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                val targetUrl = "$baseUrl/students/$cleanStudentId.json"

                val request = Request.Builder()
                    .url(targetUrl)
                    .put(body)
                    .build()

                val response = httpClient.newCall(request).execute()
                response.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }
}
