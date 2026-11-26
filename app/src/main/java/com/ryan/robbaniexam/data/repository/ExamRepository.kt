package com.ryan.robbaniexam.data.repository

import com.google.gson.Gson
import com.ryan.robbaniexam.data.model.ExamSession
import com.ryan.robbaniexam.data.model.StudentInfo

object ExamRepository {
    private val gson = Gson()

    private val defaultDemoSession = ExamSession(
        examCode = "ROBBANI123",
        title = "Ujian Penilaian Akhir Semester Moodle",
        subject = "Matematika & Umum",
        teacherName = "Ustadz Ahmad, S.Pd",
        targetClasses = listOf("7A", "7B", "8A", "9A", "X IPA", "XI IPS"),
        moodleUrl = "https://elearning.robbani.sch.id",
        token = "ROBBANI2025",
        durationMinutes = 90,
        adminPin = "123456",
        maxViolations = 3,
        strictMode = true
    )

    fun parseExamSessionFromQr(qrJson: String): ExamSession? {
        return try {
            gson.fromJson(qrJson, ExamSession::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun getExamSessionByCode(code: String): ExamSession {
        // Return demo session or customize based on entered code
        return defaultDemoSession.copy(examCode = code.uppercase())
    }

    fun createSessionJson(session: ExamSession): String {
        return gson.toJson(session)
    }

    fun createStudentJson(student: StudentInfo): String {
        return gson.toJson(student)
    }
}
