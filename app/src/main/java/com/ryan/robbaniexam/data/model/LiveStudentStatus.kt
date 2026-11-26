package com.ryan.robbaniexam.data.model

data class LiveStudentStatus(
    val studentId: String = "",
    val studentName: String = "",
    val className: String = "",
    val examCode: String = "",
    val status: String = "EXAM_IN_PROGRESS",
    val violationCount: Int = 0,
    val lastViolationReason: String = "",
    val remainingSeconds: Int = 0,
    val lastUpdated: Long = System.currentTimeMillis()
)
