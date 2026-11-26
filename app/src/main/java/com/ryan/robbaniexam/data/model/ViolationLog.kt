package com.ryan.robbaniexam.data.model

data class ViolationLog(
    val studentName: String = "",
    val className: String = "",
    val examCode: String = "",
    val violationType: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val details: String = ""
)
