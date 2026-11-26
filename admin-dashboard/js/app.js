// Robbani Exam Admin Dashboard Production Logic (With SweetAlert2 & Alarm Sound)
document.addEventListener("DOMContentLoaded", function () {

    const defaultFirebaseDbUrl = "https://robbani-exam-live-default-rtdb.firebaseio.com";
    const firebaseDbUrl = localStorage.getItem("robbani_firebase_db_url") || defaultFirebaseDbUrl;

    let exams = JSON.parse(localStorage.getItem("robbani_exams")) || [];
    let studentsStatusMap = {};
    let alertedViolationsMap = {}; // Tracks student IDs already alerted for max violations

    function saveExams() {
        localStorage.setItem("robbani_exams", JSON.stringify(exams));
        updateAllUI();
    }

    // Web Audio API Emergency Siren Alarm Synthesizer
    window.playAlarmSound = function () {
        try {
            const AudioContext = window.AudioContext || window.webkitAudioContext;
            if (!AudioContext) return;
            const ctx = new AudioContext();

            // Create oscillating alarm siren (2 tone wail)
            const osc = ctx.createOscillator();
            const gain = ctx.createGain();

            osc.type = "sawtooth";
            osc.frequency.setValueAtTime(800, ctx.currentTime);
            osc.frequency.exponentialRampToValueAtTime(1200, ctx.currentTime + 0.3);
            osc.frequency.exponentialRampToValueAtTime(800, ctx.currentTime + 0.6);
            osc.frequency.exponentialRampToValueAtTime(1200, ctx.currentTime + 0.9);
            osc.frequency.exponentialRampToValueAtTime(800, ctx.currentTime + 1.2);

            gain.gain.setValueAtTime(0.3, ctx.currentTime);
            gain.gain.exponentialRampToValueAtTime(0.01, ctx.currentTime + 1.5);

            osc.connect(gain);
            gain.connect(ctx.destination);

            osc.start();
            osc.stop(ctx.currentTime + 1.5);
        } catch (e) {
            console.warn("Audio Context playback error:", e);
        }
    };

    window.testAlarmSound = function () {
        playAlarmSound();
        if (window.Swal) {
            Swal.fire({
                title: '🔔 Tes Alarm Kecurangan',
                text: 'Suara alarm siren dan notifikasi SweetAlert2 berfungsi dengan baik!',
                icon: 'info',
                confirmButtonColor: '#2563eb'
            });
        }
    };

    // Initialize Live Firebase Realtime Sync
    try {
        if (window.firebase && !firebase.apps.length) {
            firebase.initializeApp({
                databaseURL: firebaseDbUrl
            });
        }

        if (window.firebase) {
            const dbRef = firebase.database().ref("students");
            dbRef.on("value", function (snapshot) {
                const data = snapshot.val();
                if (data) {
                    studentsStatusMap = data;
                    checkViolationsAndTriggerAlerts();
                    updateAllUI();
                }
            });
        }
    } catch (err) {
        console.warn("Firebase Live Sync Error:", err);
    }

    // Check if any student reached 3x / max violations and trigger SweetAlert2 & Alarm Sound!
    function checkViolationsAndTriggerAlerts() {
        const studentList = Object.values(studentsStatusMap);

        studentList.forEach(s => {
            const vCount = parseInt(s.violationCount) || 0;
            const studentId = s.studentId || s.id;
            const exam = exams.find(e => e.examCode === s.examCode);
            const maxAllowed = (exam && exam.maxViolations) ? parseInt(exam.maxViolations) : 3;

            // Trigger alarm & alert if violations reached maxAllowed (e.g. 3x) and not alerted yet
            if (vCount >= maxAllowed && !alertedViolationsMap[studentId]) {
                alertedViolationsMap[studentId] = true;

                // Play Audio Alarm Siren
                playAlarmSound();

                // Trigger SweetAlert2 Warning Modal
                if (window.Swal) {
                    Swal.fire({
                        title: '🚨 PERINGATAN KECURANGAN SISWA!',
                        html: `<div class="text-start p-2">
                                  <p class="mb-1"><b>Nama Siswa:</b> ${s.studentName || s.name || 'Siswa'}</p>
                                  <p class="mb-1"><b>Kelas:</b> ${s.className || '-'}</p>
                                  <p class="mb-1"><b>Kode Ujian:</b> ${s.examCode || '-'}</p>
                                  <p class="mb-1 text-danger fw-bold"><b>Jumlah Pelanggaran:</b> ${vCount} / ${maxAllowed}</p>
                                  <p class="mb-0 text-muted"><b>Catatan:</b> ${s.lastViolationReason || 'Memenuhi batas pelanggaran maksimal'}</p>
                               </div>
                               <div class="alert alert-danger mt-3 mb-0 small">
                                  <i class="fas fa-lock me-1"></i> <strong>Layar HP siswa telah TERKUNCI secara otomatis!</strong>
                               </div>`,
                        icon: 'warning',
                        confirmButtonText: '<i class="fas fa-check me-1"></i> Buka Kunci PIN Guru',
                        showCancelButton: true,
                        cancelButtonText: 'Tutup Notifikasi',
                        confirmButtonColor: '#2563eb',
                        cancelButtonColor: '#64748b',
                        focusConfirm: false
                    }).then((result) => {
                        if (result.isConfirmed) {
                            resetViolation(studentId);
                        }
                    });
                }
            }
        });
    }

    // Dynamic Summary Stat Counters Calculation
    function updateStatCards() {
        const studentList = Object.values(studentsStatusMap);

        const activeExamsCount = exams.length;
        const activeStudentsCount = studentList.filter(s => s.status === "SEDANG UJIAN" || (s.status && s.status.startsWith("PERINGATAN"))).length;
        const totalViolationsCount = studentList.reduce((sum, s) => sum + (parseInt(s.violationCount) || 0), 0);
        const lockedStudentsCount = studentList.filter(s => s.status === "TERKUNCI" || (parseInt(s.violationCount) >= 3)).length;

        document.getElementById("statActiveExams").innerText = activeExamsCount;
        document.getElementById("statActiveStudents").innerText = activeStudentsCount;
        document.getElementById("statTotalViolations").innerText = totalViolationsCount;
        document.getElementById("statLockedStudents").innerText = lockedStudentsCount;
    }

    // Render Active Exams Cards
    function renderExamsList() {
        const container = document.getElementById("examsContainer");
        if (!container) return;

        if (exams.length === 0) {
            container.innerHTML = `
                <div class="col-12 text-center p-5 card card-custom">
                    <i class="fas fa-file-invoice fa-3x text-muted mb-3"></i>
                    <h5 class="fw-bold">Belum Ada Ujian Moodle Dibuat</h5>
                    <p class="text-muted small">Klik tombol <strong>"Buat Ujian Baru"</strong> di atas untuk memasukkan link ujian Moodle sekolah Anda.</p>
                </div>`;
            return;
        }

        container.innerHTML = exams.map(exam => `
            <div class="col-md-6 col-lg-4 mb-4">
                <div class="card card-custom h-100 p-3">
                    <div class="d-flex justify-content-between align-items-start mb-2">
                        <span class="badge bg-primary rounded-pill">${exam.subject}</span>
                        <span class="badge-exam-code">${exam.examCode}</span>
                    </div>
                    <h5 class="fw-bold mt-2 text-dark">${exam.title}</h5>
                    <p class="text-muted small mb-1"><i class="fas fa-chalkboard-teacher me-1 text-primary"></i> Guru: ${exam.teacherName}</p>
                    <p class="text-muted small mb-1"><i class="fas fa-users me-1 text-primary"></i> Kelas: ${exam.targetClasses.join(", ")}</p>
                    <p class="text-muted small mb-2"><i class="fas fa-clock me-1 text-primary"></i> Durasi: ${exam.durationMinutes} m | Batas Curang: <strong>${exam.maxViolations || 3}x</strong></p>
                    <p class="text-break small text-muted mb-3 bg-light p-2 rounded"><i class="fas fa-link me-1 text-primary"></i> <a href="${exam.moodleUrl}" target="_blank" class="text-decoration-none">${exam.moodleUrl}</a></p>

                    <div class="mt-auto d-flex gap-2">
                        <button class="btn btn-sm btn-robbani flex-fill" onclick="showQrModal('${exam.examCode}')">
                            <i class="fas fa-qrcode me-1"></i> QR & Kode Ujian
                        </button>
                        <button class="btn btn-sm btn-outline-danger" onclick="deleteExam('${exam.id}')" title="Hapus Ujian">
                            <i class="fas fa-trash"></i>
                        </button>
                    </div>
                </div>
            </div>
        `).join("");
    }

    // Render Live Student Monitoring Table
    function renderStudentsTable() {
        const tbody = document.getElementById("studentsTableBody");
        if (!tbody) return;

        const classFilter = document.getElementById("filterClass")?.value || "ALL";
        const studentList = Object.values(studentsStatusMap);

        const filtered = studentList.filter(s => classFilter === "ALL" || s.className === classFilter);

        if (filtered.length === 0) {
            tbody.innerHTML = `
                <tr>
                    <td colspan="8" class="text-center p-4 text-muted">
                        <i class="fas fa-user-clock me-2"></i>Belum ada aktivitas siswa ujian pada kelas ini.
                    </td>
                </tr>`;
            return;
        }

        tbody.innerHTML = filtered.map(s => {
            const vCount = parseInt(s.violationCount) || 0;
            const exam = exams.find(e => e.examCode === s.examCode);
            const maxAllowed = (exam && exam.maxViolations) ? parseInt(exam.maxViolations) : 3;

            let badgeClass = "bg-success";
            if (vCount > 0 && vCount < maxAllowed) badgeClass = "bg-warning text-dark";
            if (vCount >= maxAllowed || s.status === "TERKUNCI") badgeClass = "bg-danger";

            return `
                <tr>
                    <td class="fw-bold">${s.studentName || s.name || 'Siswa'}</td>
                    <td><span class="badge bg-secondary">${s.className || '-'}</span></td>
                    <td><span class="badge bg-light text-dark border">${s.examCode || '-'}</span></td>
                    <td><span class="badge badge-status ${badgeClass}">${s.status || 'AKTIF'}</span></td>
                    <td class="text-center fw-bold ${vCount > 0 ? 'text-danger' : 'text-success'}">${vCount} / ${maxAllowed}</td>
                    <td class="small text-muted">${s.lastViolationReason || s.lastReason || '-'}</td>
                    <td class="fw-bold text-primary">${s.remainingSeconds ? formatTime(s.remainingSeconds) : 'Aktif'}</td>
                    <td>
                        <button class="btn btn-sm btn-outline-warning" onclick="resetViolation('${s.studentId || s.id}')">
                            <i class="fas fa-key me-1"></i> Buka PIN
                        </button>
                        <button class="btn btn-sm btn-outline-danger" onclick="removeStudentSession('${s.studentId || s.id}')">
                            <i class="fas fa-times"></i>
                        </button>
                    </td>
                </tr>
            `;
        }).join("");
    }

    function formatTime(seconds) {
        if (!seconds) return "-";
        const m = Math.floor(seconds / 60);
        const s = seconds % 60;
        return `${m}:${s < 10 ? '0' : ''}${s}`;
    }

    function updateAllUI() {
        updateStatCards();
        renderExamsList();
        renderStudentsTable();
    }

    // Exam Form Submit Handler
    const formExam = document.getElementById("formCreateExam");
    if (formExam) {
        formExam.addEventListener("submit", function (e) {
            e.preventDefault();

            const title = document.getElementById("examTitle").value.trim();
            const subject = document.getElementById("examSubject").value.trim();
            const teacherName = document.getElementById("examTeacher").value.trim();
            const moodleUrl = document.getElementById("examMoodleUrl").value.trim();
            const token = document.getElementById("examToken").value.trim();
            const duration = parseInt(document.getElementById("examDuration").value) || 90;
            const maxViolations = parseInt(document.getElementById("examMaxViolations").value) || 3;
            const adminPin = document.getElementById("examPin").value.trim() || "123456";

            const selectedClasses = Array.from(document.querySelectorAll("#examClasses input:checked")).map(cb => cb.value);

            const generatedCode = "RBN" + Math.floor(1000 + Math.random() * 9000);

            const newExam = {
                id: "EXAM-" + Date.now(),
                examCode: generatedCode,
                title: title,
                subject: subject,
                teacherName: teacherName,
                targetClasses: selectedClasses.length > 0 ? selectedClasses : ["Semua Kelas"],
                moodleUrl: moodleUrl,
                token: token,
                durationMinutes: duration,
                maxViolations: maxViolations,
                adminPin: adminPin
            };

            exams.unshift(newExam);
            saveExams();

            const modalElement = document.getElementById("modalCreateExam");
            const modal = bootstrap.Modal.getInstance(modalElement);
            if (modal) modal.hide();
            formExam.reset();

            showQrModal(generatedCode);
        });
    }

    // Window Functions for Actions & Operations
    window.showQrModal = function (examCode) {
        const exam = exams.find(e => e.examCode === examCode);
        if (!exam) return;

        document.getElementById("qrModalTitle").innerText = exam.title;
        document.getElementById("qrExamCodeDisplay").innerText = exam.examCode;
        document.getElementById("qrExamPinDisplay").innerText = exam.adminPin;
        document.getElementById("qrExamMaxViolationsDisplay").innerText = (exam.maxViolations || 3) + "x";
        document.getElementById("qrMoodleUrlDisplay").innerText = exam.moodleUrl;

        const qrContainer = document.getElementById("qrCodeCanvas");
        qrContainer.innerHTML = "";

        const qrPayloadJson = JSON.stringify({
            examCode: exam.examCode,
            title: exam.title,
            moodleUrl: exam.moodleUrl,
            token: exam.token,
            durationMinutes: exam.durationMinutes,
            maxViolations: exam.maxViolations || 3,
            adminPin: exam.adminPin
        });

        if (window.QRCode) {
            new QRCode(qrContainer, {
                text: qrPayloadJson,
                width: 220,
                height: 220
            });
        }

        const modal = new bootstrap.Modal(document.getElementById("modalQrDisplay"));
        modal.show();
    };

    window.deleteExam = function (id) {
        if (confirm("Apakah Anda yakin ingin menghapus ujian ini?")) {
            exams = exams.filter(e => e.id !== id);
            saveExams();
        }
    };

    window.resetViolation = function (studentId) {
        if (window.firebase && studentId) {
            delete alertedViolationsMap[studentId];
            firebase.database().ref(`students/${studentId}`).update({
                status: "SEDANG UJIAN",
                violationCount: 0,
                lastViolationReason: "Buka Kunci PIN Guru"
            });
            if (window.Swal) {
                Swal.fire({
                    title: 'Berhasil Buka Kunci',
                    text: 'Perintah buka kunci berhasil dikirim ke HP siswa.',
                    icon: 'success',
                    timer: 2000,
                    showConfirmButton: false
                });
            }
        }
    };

    window.removeStudentSession = function (studentId) {
        if (window.firebase && studentId) {
            delete alertedViolationsMap[studentId];
            firebase.database().ref(`students/${studentId}`).remove();
        }
    };

    window.clearFinishedStudents = function () {
        if (window.firebase) {
            const studentList = Object.values(studentsStatusMap);
            studentList.forEach(s => {
                if (s.status === "SELESAI") {
                    delete alertedViolationsMap[s.studentId];
                    firebase.database().ref(`students/${s.studentId}`).remove();
                }
            });
        }
    };

    window.exportReportToCsv = function () {
        const studentList = Object.values(studentsStatusMap);
        if (studentList.length === 0) {
            alert("Belum ada data aktivitas siswa untuk diekspor.");
            return;
        }

        let csvContent = "data:text/csv;charset=utf-8,Nama Siswa,Kelas,Kode Ujian,Status,Jumlah Pelanggaran,Catatan Terakhir,Sisa Waktu\n";
        studentList.forEach(s => {
            csvContent += `"${s.studentName || s.name}","${s.className}","${s.examCode}","${s.status}","${s.violationCount || 0}","${s.lastViolationReason || ''}","${s.remainingSeconds || ''}"\n`;
        });
        const encodedUri = encodeURI(csvContent);
        const link = document.createElement("a");
        link.setAttribute("href", encodedUri);
        link.setAttribute("download", `Laporan_Ujian_Robbani_${Date.now()}.csv`);
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
    };

    window.saveFirebaseConfig = function () {
        const dbUrl = document.getElementById("firebaseDbUrl")?.value.trim();
        if (dbUrl) {
            localStorage.setItem("robbani_firebase_db_url", dbUrl);
            alert("Pengaturan Database Server Firebase Berhasil Disimpan! Memuat ulang...");
            location.reload();
        }
    };

    document.getElementById("filterClass")?.addEventListener("change", renderStudentsTable);

    updateAllUI();
});
