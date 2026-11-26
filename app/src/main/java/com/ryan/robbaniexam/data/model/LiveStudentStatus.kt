package com.ryan.robbaniexam.data.model

data class LiveStudentStatus(
    val studentId: String = "",
    val studentName: String = "",
    val className: String = "",
    val nis: String = "",
    val examCode: String = "",
    val status: String = "SEDANG UJIAN",
    val violationCount: Int = 0,
    val lastViolationReason: String = "",
    val remainingSeconds: Int = 0,
    val lastUpdated: Long = System.currentTimeMillis(),
    val violationLogs: List<ViolationLog> = emptyList(),
    val remoteCommand: String = "",
    val remoteMessage: String = ""
)
