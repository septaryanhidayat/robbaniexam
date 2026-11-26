package com.ryan.robbaniexam.data.model

data class ExamSession(
    val examCode: String = "",
    val title: String = "",
    val subject: String = "",
    val teacherName: String = "",
    val targetClasses: List<String> = emptyList(),
    val moodleUrl: String = "",
    val token: String = "",
    val durationMinutes: Int = 90,
    val adminPin: String = "123456",
    val maxViolations: Int = 3,
    val strictMode: Boolean = true
)
