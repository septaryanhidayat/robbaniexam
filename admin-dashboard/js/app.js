// Robbani Exam Admin Dashboard Production Logic
document.addEventListener("DOMContentLoaded", function () {

    // Load initial exams from LocalStorage or empty list
    let exams = JSON.parse(localStorage.getItem("robbani_exams")) || [];

    // Load dynamic student session status from LocalStorage or empty list
    let studentsStatus = JSON.parse(localStorage.getItem("robbani_students_status")) || [];

    function saveExams() {
        localStorage.setItem("robbani_exams", JSON.stringify(exams));
        updateAllUI();
    }

    function saveStudentsStatus() {
        localStorage.setItem("robbani_students_status", JSON.stringify(studentsStatus));
        updateAllUI();
    }

    // Dynamic Summary Stat Counters Calculation
    function updateStatCards() {
        const activeExamsCount = exams.length;
        const activeStudentsCount = studentsStatus.filter(s => s.status === "SEDANG UJIAN" || s.status.startsWith("PERINGATAN")).length;
        const totalViolationsCount = studentsStatus.reduce((sum, s) => sum + (parseInt(s.violations) || 0), 0);
        const lockedStudentsCount = studentsStatus.filter(s => s.status === "TERKUNCI" || s.violations >= 3).length;

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
                    <p class="text-muted small mb-2"><i class="fas fa-clock me-1 text-primary"></i> Durasi: ${exam.durationMinutes} m | PIN: <strong>${exam.adminPin}</strong></p>
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

        const filtered = studentsStatus.filter(s => classFilter === "ALL" || s.className === classFilter);

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
            let badgeClass = "bg-success";
            if (s.violations === 1 || s.violations === 2) badgeClass = "bg-warning text-dark";
            if (s.violations >= 3 || s.status === "TERKUNCI") badgeClass = "bg-danger";

            return `
                <tr>
                    <td class="fw-bold">${s.name}</td>
                    <td><span class="badge bg-secondary">${s.className}</span></td>
                    <td><span class="badge bg-light text-dark border">${s.examCode}</span></td>
                    <td><span class="badge badge-status ${badgeClass}">${s.status}</span></td>
                    <td class="text-center fw-bold ${s.violations > 0 ? 'text-danger' : 'text-success'}">${s.violations} / 3</td>
                    <td class="small text-muted">${s.lastReason || '-'}</td>
                    <td class="fw-bold text-primary">${s.remaining || '-'}</td>
                    <td>
                        <button class="btn btn-sm btn-outline-warning" onclick="resetViolation('${s.id}')">
                            <i class="fas fa-key me-1"></i> Buka PIN
                        </button>
                        <button class="btn btn-sm btn-outline-danger" onclick="removeStudentSession('${s.id}')">
                            <i class="fas fa-times"></i>
                        </button>
                    </td>
                </tr>
            `;
        }).join("");
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
                adminPin: adminPin,
                maxViolations: 3
            };

            exams.unshift(newExam);
            saveExams();

            const modalElement = document.getElementById("modalCreateExam");
            const modal = bootstrap.Modal.getInstance(modalElement);
            if (modal) modal.hide();
            formExam.reset();

            // Auto-display QR Modal for the newly created exam
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
        document.getElementById("qrMoodleUrlDisplay").innerText = exam.moodleUrl;

        const qrContainer = document.getElementById("qrCodeCanvas");
        qrContainer.innerHTML = "";

        const qrPayloadJson = JSON.stringify({
            examCode: exam.examCode,
            title: exam.title,
            moodleUrl: exam.moodleUrl,
            token: exam.token,
            durationMinutes: exam.durationMinutes,
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
        const student = studentsStatus.find(s => s.id === studentId);
        if (student) {
            student.violations = 0;
            student.status = "SEDANG UJIAN";
            student.lastReason = "Buka Kunci PIN Guru";
            saveStudentsStatus();
            alert(`Siswa ${student.name} berhasil dibuka kunci.`);
        }
    };

    window.removeStudentSession = function (studentId) {
        studentsStatus = studentsStatus.filter(s => s.id !== studentId);
        saveStudentsStatus();
    };

    window.clearFinishedStudents = function () {
        studentsStatus = studentsStatus.filter(s => s.status === "SEDANG UJIAN" || s.status.startsWith("PERINGATAN") || s.status === "TERKUNCI");
        saveStudentsStatus();
    };

    window.exportReportToCsv = function () {
        if (studentsStatus.length === 0) {
            alert("Belum ada data aktivitas siswa untuk diekspor.");
            return;
        }

        let csvContent = "data:text/csv;charset=utf-8,Nama Siswa,Kelas,Kode Ujian,Status,Jumlah Pelanggaran,Catatan Terakhir,Sisa Waktu\n";
        studentsStatus.forEach(s => {
            csvContent += `"${s.name}","${s.className}","${s.examCode}","${s.status}","${s.violations}","${s.lastReason}","${s.remaining}"\n`;
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
            alert("Pengaturan Database Server Firebase Berhasil Disimpan!");
        } else {
            alert("Pengaturan Server Disimpan dalam Mode Local Realtime.");
        }
        const modal = bootstrap.Modal.getInstance(document.getElementById("modalServerConfig"));
        if (modal) modal.hide();
    };

    // Cross-tab / Multi-device Sync Listener via Storage Event
    window.addEventListener("storage", function (e) {
        if (e.key === "robbani_exams") {
            exams = JSON.parse(e.newValue) || [];
        }
        if (e.key === "robbani_students_status") {
            studentsStatus = JSON.parse(e.newValue) || [];
        }
        updateAllUI();
    });

    // Initial render
    updateAllUI();
});
