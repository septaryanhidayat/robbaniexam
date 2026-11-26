package com.ryan.robbaniexam.data.repository

import com.google.gson.Gson
import com.ryan.robbaniexam.data.model.ExamSession
import com.ryan.robbaniexam.data.model.StudentInfo

object ExamRepository {
    private val gson = Gson()
    private val cachedSessions = mutableMapOf<String, ExamSession>()

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

        // 1. Check if input is a direct Moodle / HTTP URL
        if (cleanInput.startsWith("http://", ignoreCase = true) || cleanInput.startsWith("https://", ignoreCase = true)) {
            val session = ExamSession(
                examCode = "CUSTOM-" + System.currentTimeMillis().toString().takeLast(4),
                title = "Ujian Moodle LMS Sekolah",
                moodleUrl = cleanInput,
                durationMinutes = 90,
                adminPin = "123456"
            )
            registerSession(session)
            return session
        }

        // 2. Check cached/scanned sessions by Exam Code
        val cached = cachedSessions[cleanInput.uppercase()]
        if (cached != null) {
            return cached
        }

        // 3. Fallback: Create dynamic session using input as code or domain
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

    fun createSessionJson(session: ExamSession): String {
        return gson.toJson(session)
    }

    fun createStudentJson(student: StudentInfo): String {
        return gson.toJson(student)
    }
}
