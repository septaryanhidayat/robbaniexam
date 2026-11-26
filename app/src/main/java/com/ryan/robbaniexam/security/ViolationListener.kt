package com.ryan.robbaniexam.security

interface ViolationListener {
    fun onViolationDetected(type: ViolationType, message: String, currentViolations: Int, maxViolations: Int)
    fun onMaxViolationsExceeded(type: ViolationType, message: String)
}
