package com.ryan.robbaniexam

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.ryan.robbaniexam.data.model.ExamSession
import com.ryan.robbaniexam.data.model.StudentInfo
import com.ryan.robbaniexam.data.repository.ExamRepository
import com.ryan.robbaniexam.security.ViolationType
import com.ryan.robbaniexam.ui.exam.ExamActivity
import com.ryan.robbaniexam.ui.main.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class ExamFlowInstrumentedTest {

    private lateinit var mockFirebase: LocalFirebaseSseServer
    private lateinit var originalUrl: String

    @Before
    fun setUp() {
        originalUrl = ExamRepository.firebaseDbUrl
        mockFirebase = LocalFirebaseSseServer()
        mockFirebase.start()
        ExamRepository.firebaseDbUrl = "http://127.0.0.1:${mockFirebase.port}"
    }

    @After
    fun tearDown() {
        ExamRepository.firebaseDbUrl = originalUrl
        mockFirebase.stop()
    }

    @Test
    fun mainActivity_displaysLiveMassAnnouncementInRealTime() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            SystemClock.sleep(800)
            scenario.onActivity { activity ->
                val card = activity.findViewById<View>(R.id.cardAnnouncement)
                assertEquals(View.GONE, card.visibility)
            }

            // Simulate admin publishing mass announcement while student is on home screen
            mockFirebase.publishAnnouncement("Ujian dimulai pukul 10:30 WIB", 1795750001000L)

            val visible = waitUntil(5000L) {
                var result = false
                scenario.onActivity { activity ->
                    val card = activity.findViewById<View>(R.id.cardAnnouncement)
                    val tv = activity.findViewById<TextView>(R.id.tvAnnouncementText)
                    result = card.visibility == View.VISIBLE &&
                        tv.text.toString() == "Ujian dimulai pukul 10:30 WIB"
                }
                result
            }
            assertTrue("Home announcement card should update in real time", visible)
        }
    }

    @Test
    fun examActivity_remoteUnlockAndMassMessageWorkEndToEndWithoutRelocking() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val student = StudentInfo(
            studentId = "STU-TEST-01",
            name = "Budi Santoso",
            className = "7A",
            nis = "202601"
        )
        val session = ExamSession(
            examCode = "RBN9999",
            title = "Ujian Uji Coba",
            subject = "IT",
            teacherName = "Pengawas",
            moodleUrl = "about:blank",
            durationMinutes = 60,
            adminPin = "123456",
            maxViolations = 3
        )
        val intent = Intent(context, ExamActivity::class.java).apply {
            putExtra("EXTRA_STUDENT_JSON", Gson().toJson(student))
            putExtra("EXTRA_SESSION_JSON", Gson().toJson(session))
        }

        ActivityScenario.launch<ExamActivity>(intent).use { scenario ->
            // Wait for SSE streams to connect
            assertTrue("Student SSE stream should connect", waitUntil(5000L) { mockFirebase.studentSseCount() > 0 })
            assertTrue("Announcement SSE stream should connect", waitUntil(5000L) { mockFirebase.announcementSseCount() > 0 })

            // Trigger 3 violations to lock the exam screen
            scenario.onActivity { activity ->
                val sec = activity.securityManagerForTest()
                assertTrue(sec.recordViolation(ViolationType.APP_SWITCHED, "Pelanggaran 1"))
            }
            SystemClock.sleep(1600)
            scenario.onActivity { activity ->
                val sec = activity.securityManagerForTest()
                assertTrue(sec.recordViolation(ViolationType.APP_SWITCHED, "Pelanggaran 2"))
            }
            SystemClock.sleep(1600)
            scenario.onActivity { activity ->
                val sec = activity.securityManagerForTest()
                assertTrue(sec.recordViolation(ViolationType.APP_SWITCHED, "Pelanggaran 3"))
                assertTrue(sec.isExamLocked)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.layoutLockedScreen).visibility)
                assertEquals("3/3", activity.findViewById<TextView>(R.id.tvViolationBadge).text.toString())
            }

            // Wait for TERKUNCI patch to reach server
            assertTrue("Server should receive TERKUNCI status", waitUntil(5000L) {
                mockFirebase.lastStudentStatus == "TERKUNCI"
            })

            // Simulate Dashboard Remote Unlock
            mockFirebase.pushStudentPatch(
                """{"status":"SEDANG UJIAN","violationCount":0,"lastViolationReason":"Buka Kunci Jarak Jauh Pengawas","remoteCommand":"UNLOCK","remoteMessage":"","remoteCommandAt":1795750005000}"""
            )

            // Verify screen unlocks and badge resets
            val unlocked = waitUntil(5000L) {
                var ok = false
                scenario.onActivity { activity ->
                    val sec = activity.securityManagerForTest()
                    val lockView = activity.findViewById<View>(R.id.layoutLockedScreen)
                    val badge = activity.findViewById<TextView>(R.id.tvViolationBadge)
                    ok = !sec.isExamLocked &&
                        sec.violationCount == 0 &&
                        lockView.visibility == View.GONE &&
                        badge.text.toString() == "0/3"
                }
                ok
            }
            assertTrue("Exam screen must unlock via remote UNLOCK command", unlocked)

            // Wait across multiple 500ms watchdog ticks to verify it does NOT immediately re-lock!
            SystemClock.sleep(2000)
            scenario.onActivity { activity ->
                val sec = activity.securityManagerForTest()
                assertFalse("Exam must stay unlocked after remote unlock", sec.isExamLocked)
                assertEquals(0, sec.violationCount)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.layoutLockedScreen).visibility)
            }

            // Test private MESSAGE delivery
            mockFirebase.pushStudentPatch(
                """{"remoteCommand":"MESSAGE","remoteMessage":"Harap fokus ke soal nomor 5","remoteCommandAt":1795750010000}"""
            )
            val gotPrivateMsg = waitUntil(5000L) {
                var ok = false
                scenario.onActivity { activity ->
                    val banner = activity.findViewById<View>(R.id.bannerProctorNotice)
                    val tv = activity.findViewById<TextView>(R.id.tvProctorNotice)
                    ok = banner.visibility == View.VISIBLE &&
                        tv.text.toString().contains("Harap fokus ke soal nomor 5")
                }
                ok
            }
            assertTrue("Private message must show on ExamActivity", gotPrivateMsg)

            // Test mass announcement delivery during active exam
            mockFirebase.publishAnnouncement("Pengumuman masal: waktu kurang 10 menit!", 1795750020000L)
            val gotMassMsg = waitUntil(5000L) {
                var ok = false
                scenario.onActivity { activity ->
                    val banner = activity.findViewById<View>(R.id.bannerProctorNotice)
                    val tv = activity.findViewById<TextView>(R.id.tvProctorNotice)
                    ok = banner.visibility == View.VISIBLE &&
                        tv.text.toString().contains("Pengumuman masal: waktu kurang 10 menit!")
                }
                ok
            }
            assertTrue("Mass announcement must show in real time during ExamActivity", gotMassMsg)
        }
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            SystemClock.sleep(100)
        }
        return condition()
    }

    private class LocalFirebaseSseServer {
        private var serverSocket: ServerSocket? = null
        private val running = AtomicBoolean(false)
        private val studentStreams = CopyOnWriteArrayList<OutputStream>()
        private val announcementStreams = CopyOnWriteArrayList<OutputStream>()

        @Volatile
        var lastStudentStatus: String = ""

        @Volatile
        private var currentAnnouncementJson: String = "null"

        @Volatile
        private var currentStudentJson: String = """{"status":"SEDANG UJIAN","violationCount":0,"remoteCommand":"","remoteMessage":""}"""

        val port: Int
            get() = serverSocket?.localPort ?: 0

        fun start() {
            serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            running.set(true)
            Thread {
                while (running.get()) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        Thread { handleClient(socket) }.start()
                    } catch (_: Exception) {
                        if (!running.get()) break
                    }
                }
            }.start()
        }

        fun stop() {
            running.set(false)
            studentStreams.forEach { runCatching { it.close() } }
            announcementStreams.forEach { runCatching { it.close() } }
            runCatching { serverSocket?.close() }
        }

        fun studentSseCount(): Int = studentStreams.size
        fun announcementSseCount(): Int = announcementStreams.size

        fun publishAnnouncement(message: String, updatedAt: Long) {
            val escaped = message.replace("\"", "\\\"")
            currentAnnouncementJson = """{"message":"$escaped","updatedAt":$updatedAt}"""
            val eventPayload = """{"path":"/","data":$currentAnnouncementJson}"""
            broadcastSse(announcementStreams, "put", eventPayload)
        }

        fun pushStudentPatch(dataJson: String) {
            val patchObj = JsonParser.parseString(dataJson).asJsonObject
            val stateObj = JsonParser.parseString(currentStudentJson).asJsonObject
            patchObj.entrySet().forEach { (k, v) -> stateObj.add(k, v) }
            currentStudentJson = stateObj.toString()
            broadcastSse(studentStreams, "patch", """{"path":"/","data":$dataJson}""")
        }

        private fun broadcastSse(streams: CopyOnWriteArrayList<OutputStream>, event: String, data: String) {
            val frame = "event: $event\ndata: $data\n\n".toByteArray(StandardCharsets.UTF_8)
            val dead = mutableListOf<OutputStream>()
            for (os in streams) {
                try {
                    synchronized(os) {
                        os.write(frame)
                        os.flush()
                    }
                } catch (_: Exception) {
                    dead.add(os)
                }
            }
            streams.removeAll(dead.toSet())
        }

        private fun handleClient(socket: Socket) {
            try {
                val input = BufferedInputStream(socket.getInputStream())
                val os = socket.getOutputStream()

                val headerBuf = ByteArrayOutputStream()
                var prev4 = 0
                while (true) {
                    val b = input.read()
                    if (b == -1) return
                    headerBuf.write(b)
                    prev4 = (prev4 shl 8) or (b and 0xFF)
                    if (prev4 == 0x0D0A0D0A) break
                }
                val headersText = headerBuf.toString("UTF-8")
                val lines = headersText.split("\r\n")
                val reqLine = lines.firstOrNull() ?: return
                val parts = reqLine.split(" ")
                val method = parts.getOrNull(0) ?: "GET"
                val path = parts.getOrNull(1) ?: "/"

                var contentLength = 0
                var isSse = false
                for (line in lines) {
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                    if (line.startsWith("Accept:", ignoreCase = true) && line.contains("text/event-stream", ignoreCase = true)) {
                        isSse = true
                    }
                }

                var bodyText = ""
                if (contentLength > 0) {
                    val bodyBytes = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = input.read(bodyBytes, read, contentLength - read)
                        if (n <= 0) break
                        read += n
                    }
                    bodyText = String(bodyBytes, 0, read, StandardCharsets.UTF_8)
                }

                if (method == "GET" && isSse) {
                    val sseHeaders = ("HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/event-stream; charset=utf-8\r\n" +
                        "Cache-Control: no-cache\r\n" +
                        "Connection: keep-alive\r\n\r\n").toByteArray(StandardCharsets.UTF_8)
                    os.write(sseHeaders)
                    if (path.contains("/settings/announcement")) {
                        val init = "event: put\ndata: {\"path\":\"/\",\"data\":$currentAnnouncementJson}\n\n"
                        os.write(init.toByteArray(StandardCharsets.UTF_8))
                        os.flush()
                        announcementStreams.add(os)
                    } else if (path.contains("/students/")) {
                        val init = "event: put\ndata: {\"path\":\"/\",\"data\":$currentStudentJson}\n\n"
                        os.write(init.toByteArray(StandardCharsets.UTF_8))
                        os.flush()
                        studentStreams.add(os)
                    } else {
                        os.flush()
                    }
                    return
                }

                if (method == "PATCH" && path.contains("/students/")) {
                    val patchObj = JsonParser.parseString(bodyText).asJsonObject
                    val stateObj = JsonParser.parseString(currentStudentJson).asJsonObject
                    patchObj.entrySet().forEach { (k, v) -> stateObj.add(k, v) }
                    currentStudentJson = stateObj.toString()
                    patchObj.get("status")?.takeIf { !it.isJsonNull }?.asString?.let { lastStudentStatus = it }
                    sendJson(os, currentStudentJson)
                    socket.close()
                    return
                }

                val respJson = when {
                    path.contains("/settings/announcement") -> currentAnnouncementJson
                    path.contains("/students/") -> currentStudentJson
                    path.contains("/classes") -> "{}"
                    else -> "null"
                }
                sendJson(os, respJson)
                socket.close()
            } catch (_: Exception) {
                runCatching { socket.close() }
            }
        }

        private fun sendJson(os: OutputStream, json: String) {
            val bodyBytes = json.toByteArray(StandardCharsets.UTF_8)
            val hdr = ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bodyBytes.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray(StandardCharsets.UTF_8)
            os.write(hdr)
            os.write(bodyBytes)
            os.flush()
        }
    }
}
