// Robbani Exam Admin Dashboard Logic
document.addEventListener("DOMContentLoaded", function () {
    let exams = JSON.parse(localStorage.getItem("robbani_exams")) || [
        {
            id: "EXAM-101",
            examCode: "ROBBANI7A",
            title: "Ujian Penilaian Akhir Semester Matematika",
            subject: "Matematika",
            teacherName: "Ustadz Ahmad, S.Pd",
            targetClasses: ["7A", "7B"],
            moodleUrl: "https://elearning.robbani.sch.id",
            token: "ROBBANI2025",
            durationMinutes: 90,
            adminPin: "123456",
            maxViolations: 3
        }
    ];

    let studentsStatus = [
        { id: "1", name: "Ahmad Fauzi", className: "7A", nis: "2024001", examCode: "ROBBANI7A", status: "SEDANG UJIAN", violations: 0, lastReason: "-", remaining: "01:15:30" },
        { id: "2", name: "Budi Santoso", className: "7A", nis: "2024002", examCode: "ROBBANI7A", status: "PERINGATAN 1x", violations: 1, lastReason: "Pindah Aplikasi (Home Pressed)", remaining: "01:12:10" },
        { id: "3", name: "Citra Dewi", className: "7B", nis: "2024003", examCode: "ROBBANI7A", status: "SEDANG UJIAN", violations: 0, lastReason: "-", remaining: "01:20:00" },
        { id: "4", name: "Doni Pratama", className: "7A", nis: "2024004", examCode: "ROBBANI7A", status: "TERKUNCI", violations: 3, lastReason: "Batas Pelanggaran Terlampaui (Split Screen)", remaining: "00:45:00" }
    ];

    function saveExams() {
        localStorage.setItem("robbani_exams", JSON.stringify(exams));
    }

    function renderExamsList() {
        const container = document.getElementById("examsContainer");
        if (!container) return;

        if (exams.length === 0) {
            container.innerHTML = `<div class="text-center p-4 text-muted">Belum ada ujian yang dibuat. Silakan klik "Buat Ujian Baru".</div>`;
            return;
        }

        container.innerHTML = exams.map(exam => `
            <div class="col-md-6 col-lg-4 mb-4">
                <div class="card card-custom h-100 p-3">
                    <div class="d-flex justify-content-between align-items-start mb-2">
                        <span class="badge bg-danger rounded-pill">${exam.subject}</span>
                        <span class="badge-exam-code">${exam.examCode}</span>
                    </div>
                    <h5 class="fw-bold mt-2">${exam.title}</h5>
                    <p class="text-muted small mb-2"><i class="fas fa-chalkboard-teacher me-1"></i> Guru: ${exam.teacherName}</p>
                    <p class="text-muted small mb-2"><i class="fas fa-users me-1"></i> Kelas: ${exam.targetClasses.join(", ")}</p>
                    <p class="text-muted small mb-3"><i class="fas fa-clock me-1"></i> Durasi: ${exam.durationMinutes} Menit | PIN: <strong>${exam.adminPin}</strong></p>

                    <div class="mt-auto d-flex gap-2">
                        <button class="btn btn-sm btn-robbani flex-fill" onclick="showQrModal('${exam.examCode}')">
                            <i class="fas fa-qrcode me-1"></i> QR & Kode
                        </button>
                        <button class="btn btn-sm btn-outline-danger" onclick="deleteExam('${exam.id}')">
                            <i class="fas fa-trash"></i>
                        </button>
                    </div>
                </div>
            </div>
        `).join("");
    }

    function renderStudentsTable() {
        const tbody = document.getElementById("studentsTableBody");
        if (!tbody) return;

        const classFilter = document.getElementById("filterClass")?.value || "ALL";

        const filtered = studentsStatus.filter(s => classFilter === "ALL" || s.className === classFilter);

        tbody.innerHTML = filtered.map(s => {
            let badgeClass = "bg-success";
            if (s.violations === 1 || s.violations === 2) badgeClass = "bg-warning text-dark";
            if (s.violations >= 3 || s.status === "TERKUNCI") badgeClass = "bg-danger";

            return `
                <tr>
                    <td class="fw-bold">${s.name}</td>
                    <td><span class="badge bg-secondary">${s.className}</span></td>
                    <td>${s.nis}</td>
                    <td><span class="badge badge-status ${badgeClass}">${s.status}</span></td>
                    <td class="text-center fw-bold ${s.violations > 0 ? 'text-danger' : 'text-success'}">${s.violations} / 3</td>
                    <td class="small text-muted">${s.lastReason}</td>
                    <td class="fw-bold text-primary">${s.remaining}</td>
                    <td>
                        <button class="btn btn-xs btn-outline-warning" onclick="resetViolation('${s.id}')">
                            <i class="fas fa-key me-1"></i> Reset PIN
                        </button>
                    </td>
                </tr>
            `;
        }).join("");
    }

    // Exam Form Submit
    const formExam = document.getElementById("formCreateExam");
    if (formExam) {
        formExam.addEventListener("submit", function (e) {
            e.preventDefault();

            const title = document.getElementById("examTitle").value;
            const subject = document.getElementById("examSubject").value;
            const teacherName = document.getElementById("examTeacher").value;
            const moodleUrl = document.getElementById("examMoodleUrl").value;
            const token = document.getElementById("examToken").value;
            const duration = parseInt(document.getElementById("examDuration").value) || 90;
            const adminPin = document.getElementById("examPin").value || "123456";

            const selectedClasses = Array.from(document.querySelectorAll("#examClasses input:checked")).map(cb => cb.value);

            const randomCode = "RBN" + Math.floor(1000 + Math.random() * 9000);

            const newExam = {
                id: "EXAM-" + Date.now(),
                examCode: randomCode,
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
            renderExamsList();

            const modal = bootstrap.Modal.getInstance(document.getElementById("modalCreateExam"));
            if (modal) modal.hide();
            formExam.reset();
            alert("Ujian Berhasil Dibuat dengan Kode Ujian: " + randomCode);
        });
    }

    // Window Functions for Global Handlers
    window.showQrModal = function (examCode) {
        const exam = exams.find(e => e.examCode === examCode);
        if (!exam) return;

        document.getElementById("qrModalTitle").innerText = exam.title;
        document.getElementById("qrExamCodeDisplay").innerText = exam.examCode;
        document.getElementById("qrExamPinDisplay").innerText = exam.adminPin;
        document.getElementById("qrMoodleUrlDisplay").innerText = exam.moodleUrl;

        const qrContainer = document.getElementById("qrCodeCanvas");
        qrContainer.innerHTML = "";

        const qrData = JSON.stringify({
            examCode: exam.examCode,
            title: exam.title,
            moodleUrl: exam.moodleUrl,
            token: exam.token,
            durationMinutes: exam.durationMinutes,
            adminPin: exam.adminPin
        });

        if (window.QRCode) {
            new QRCode(qrContainer, {
                text: qrData,
                width: 200,
                height: 200
            });
        }

        const modal = new bootstrap.Modal(document.getElementById("modalQrDisplay"));
        modal.show();
    };

    window.deleteExam = function (id) {
        if (confirm("Apakah Anda yakin ingin menghapus ujian ini?")) {
            exams = exams.filter(e => e.id !== id);
            saveExams();
            renderExamsList();
        }
    };

    window.resetViolation = function (studentId) {
        const student = studentsStatus.find(s => s.id === studentId);
        if (student) {
            student.violations = 0;
            student.status = "SEDANG UJIAN";
            student.lastReason = "Buka Kunci PIN Guru";
            renderStudentsTable();
            alert(`Siswa ${student.name} berhasil dibuka kunci.`);
        }
    };

    window.exportReportToCsv = function () {
        let csvContent = "data:text/csv;charset=utf-8,Nama Siswa,Kelas,NIS,Status,Pelanggaran,Catatan\n";
        studentsStatus.forEach(s => {
            csvContent += `"${s.name}","${s.className}","${s.nis}","${s.status}","${s.violations}","${s.lastReason}"\n`;
        });
        const encodedUri = encodeURI(csvContent);
        const link = document.createElement("a");
        link.setAttribute("href", encodedUri);
        link.setAttribute("download", `Laporan_Pelanggaran_Ujian_${Date.now()}.csv`);
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
    };

    document.getElementById("filterClass")?.addEventListener("change", renderStudentsTable);

    renderExamsList();
    renderStudentsTable();
});
