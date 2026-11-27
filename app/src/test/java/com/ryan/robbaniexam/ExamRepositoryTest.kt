package com.ryan.robbaniexam

import com.ryan.robbaniexam.data.model.ExamSession
import com.ryan.robbaniexam.data.repository.ExamRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExamRepositoryTest {

    @Before
    fun setUp() {
        ExamRepository.clearCache()
    }

    @Test
    fun firebaseUrl_matchesConfiguredSchoolDatabase() {
        assertEquals("https://robbaniexam-default-rtdb.firebaseio.com", ExamRepository.firebaseDbUrl)
    }

    @Test
    fun parseExamSessionFromQr_parsesValidJsonAndCachesSession() {
        val qrJson = """
            {
              "examCode": "RBN2026",
              "title": "PAS Matematika",
              "subject": "Matematika",
              "moodleUrl": "https://elearning.robbani.sch.id/mod/quiz/view.php?id=12",
              "durationMinutes": 90,
              "maxViolations": 4,
              "adminPin": "654321"
            }
        """.trimIndent()

        val session = ExamRepository.parseExamSessionFromQr(qrJson)
        assertNotNull(session)
        assertEquals("RBN2026", session?.examCode)
        assertEquals(4, session?.maxViolations)
        assertEquals("654321", session?.adminPin)

        val cached = ExamRepository.getExamSessionByInput("rbn2026")
        assertEquals("PAS Matematika", cached.title)
    }

    @Test
    fun parseExamSessionFromQr_acceptsPlainUrlAndRejectsInvalidText() {
        val urlSession = ExamRepository.parseExamSessionFromQr("https://elearning.robbani.sch.id/quiz/1")
        assertNotNull(urlSession)
        assertEquals("https://elearning.robbani.sch.id/quiz/1", urlSession?.moodleUrl)

        assertNull(ExamRepository.parseExamSessionFromQr("not-json-and-not-url"))
        assertNull(ExamRepository.parseExamSessionFromQr(""))
    }

    @Test
    fun getExamSessionByInput_handlesUrlsDomainsAndCodes() {
        val direct = ExamRepository.getExamSessionByInput("https://lms.robbani.sch.id/exam")
        assertEquals("https://lms.robbani.sch.id/exam", direct.moodleUrl)

        val domain = ExamRepository.getExamSessionByInput("elearning.robbani.sch.id")
        assertEquals("https://elearning.robbani.sch.id", domain.moodleUrl)

        ExamRepository.registerSession(
            ExamSession(examCode = "RBN777", title = "Fiqih", moodleUrl = "https://elearning.robbani.sch.id/fiqih")
        )
        val fromCache = ExamRepository.getExamSessionByInput("RBN777")
        assertEquals("Fiqih", fromCache.title)
        assertTrue(fromCache.moodleUrl.endsWith("/fiqih"))
    }

    @Test
    fun sanitizeId_stripsUnsafeFirebaseCharacters() {
        assertEquals("STU_123_abc", ExamRepository.sanitizeId("STU.123/abc"))
        assertEquals("STU_UNKNOWN", ExamRepository.sanitizeId(""))
    }

    @Test
    fun parseSseEventPayload_parsesPutAndPatchCommands() {
        val patchPayload = """{"path":"/","data":{"remoteCommand":"MESSAGE","remoteMessage":"Harap fokus"}}"""
        val patchResult = ExamRepository.parseSseEventPayload("patch", patchPayload)
        assertNotNull(patchResult)
        assertEquals("MESSAGE", patchResult?.remoteCommand)
        assertEquals("Harap fokus", patchResult?.remoteMessage)

        val putPayload = """{"path":"/","data":{"status":"SEDANG UJIAN","violationCount":0,"remoteCommand":"UNLOCK","remoteMessage":"","remoteCommandAt":1795749000000}}"""
        val putResult = ExamRepository.parseSseEventPayload("put", putPayload)
        assertNotNull(putResult)
        assertEquals("UNLOCK", putResult?.remoteCommand)
        assertEquals("SEDANG UJIAN", putResult?.status)
        assertEquals(0, putResult?.violationCount)
        assertEquals(1795749000000L, putResult?.remoteCommandAt)

        val statusOnly = ExamRepository.parseSseEventPayload("put", """{"path":"/status","data":"SEDANG UJIAN"}""")
        assertEquals("SEDANG UJIAN", statusOnly?.status)
    }

    @Test
    fun parseAnnouncementSsePayload_parsesObjectMessageAndClear() {
        val putObj = ExamRepository.parseAnnouncementSsePayload(
            "put",
            """{"path":"/","data":{"message":"Waktu tersisa 10 menit","updatedAt":1795749111000}}"""
        )
        assertNotNull(putObj)
        assertEquals("Waktu tersisa 10 menit", putObj?.message)
        assertEquals(1795749111000L, putObj?.updatedAt)

        val cleared = ExamRepository.parseAnnouncementSsePayload(
            "put",
            """{"path":"/","data":null}"""
        )
        assertNotNull(cleared)
        assertEquals("", cleared?.message)
        assertEquals(0L, cleared?.updatedAt)
    }
}

