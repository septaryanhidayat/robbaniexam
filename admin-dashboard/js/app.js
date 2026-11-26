// Robbani Exam Admin & Multi-Teacher Dashboard v2.0
document.addEventListener("DOMContentLoaded", function () {

    const DEFAULT_FIREBASE_URL = "https://robbaniexam-default-rtdb.firebaseio.com";
    const LEGACY_TYPO_URL = "https://robbani-exam-live-default-rtdb.firebaseio.com";
    const DEFAULT_CLASSES = [
        { id: "7A", name: "7A", level: "SMP" },
        { id: "7B", name: "7B", level: "SMP" },
        { id: "7C", name: "7C", level: "SMP" },
        { id: "8A", name: "8A", level: "SMP" },
        { id: "8B", name: "8B", level: "SMP" },
        { id: "9A", name: "9A", level: "SMP" },
        { id: "X_IPA_1", name: "X IPA 1", level: "SMA" },
        { id: "XI_IPS_1", name: "XI IPS 1", level: "SMA" },
        { id: "XII_IPA_1", name: "XII IPA 1", level: "SMA" }
    ];

    let savedUrl = (localStorage.getItem("robbani_firebase_db_url") || "").trim();
    if (!savedUrl || savedUrl === LEGACY_TYPO_URL || savedUrl === LEGACY_TYPO_URL + "/") {
        savedUrl = DEFAULT_FIREBASE_URL;
        localStorage.setItem("robbani_firebase_db_url", savedUrl);
    }
    const firebaseDbUrl = savedUrl.replace(/\/+$/, "");

    const dbInput = document.getElementById("firebaseDbUrl");
    if (dbInput) dbInput.value = firebaseDbUrl;

    let examsMap = {};
    try {
        const localExams = JSON.parse(localStorage.getItem("robbani_exams") || "[]");
        localExams.forEach(e => { if (e && e.examCode) examsMap[e.examCode] = e; });
    } catch (_) {}

    let classesMap = {};
    let teachersMap = {};
    let studentsStatusMap = {};
    let alertedViolationsMap = {};
    let adminPasswordSetting = "robbani2026";
    let currentUser = null;
    try {
        currentUser = JSON.parse(localStorage.getItem("robbani_auth_session") || "null");
    } catch (_) { currentUser = null; }

    let alarmMuted = localStorage.getItem("robbani_alarm_muted") === "true";
    let currentQrExamCode = "";

    function escapeHtml(str) {
        return String(str || "")
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;")
            .replace(/'/g, "&#39;");
    }

    function sanitizeKey(str) {
        return String(str || "").trim().replace(/[^a-zA-Z0-9_-]/g, "_");
    }

    function showCenterSwal({ title, html = "", text = "", icon = "success", timer = 2200, showConfirmButton = true, confirmButtonText = "Tutup" }) {
        if (!window.Swal) return;
        return Swal.fire({
            title,
            html: html || undefined,
            text: text || undefined,
            icon,
            width: "36rem",
            position: "center",
            confirmButtonText,
            confirmButtonColor: "#2563eb",
            showConfirmButton,
            timer: timer || undefined,
            timerProgressBar: Boolean(timer),
            customClass: { popup: "robbani-swal-wide" }
        });
    }

    function updateMuteButtonUI() {
        const btn = document.getElementById("btnToggleMute");
        const label = document.getElementById("muteLabel");
        if (!btn || !label) return;
        if (alarmMuted) {
            label.innerText = "Alarm Bisu";
            btn.querySelector("i").className = "fas fa-bell-slash me-1 text-warning";
        } else {
            label.innerText = "Alarm Aktif";
            btn.querySelector("i").className = "fas fa-bell me-1";
        }
    }
    updateMuteButtonUI();

    window.toggleAlarmMute = function () {
        alarmMuted = !alarmMuted;
        localStorage.setItem("robbani_alarm_muted", String(alarmMuted));
        updateMuteButtonUI();
    };

    window.playAlarmSound = function (forcePlay = false) {
        if (alarmMuted && !forcePlay) return;
        try {
            const AudioContext = window.AudioContext || window.webkitAudioContext;
            if (!AudioContext) return;
            const ctx = new AudioContext();
            const osc = ctx.createOscillator();
            const gain = ctx.createGain();
            osc.type = "sawtooth";
            const now = ctx.currentTime;
            for (let i = 0; i < 6; i++) {
                osc.frequency.setValueAtTime(820, now + i * 0.6);
                osc.frequency.exponentialRampToValueAtTime(1280, now + i * 0.6 + 0.3);
                osc.frequency.exponentialRampToValueAtTime(820, now + (i + 1) * 0.6);
            }
            gain.gain.setValueAtTime(0.28, now);
            gain.gain.setValueAtTime(0.28, now + 3.2);
            gain.gain.exponentialRampToValueAtTime(0.005, now + 3.6);
            osc.connect(gain);
            gain.connect(ctx.destination);
            osc.start(now);
            osc.stop(now + 3.65);
        } catch (e) {
            console.warn("Audio Context error:", e);
        }
    };

    window.testAlarmSound = function () {
        playAlarmSound(true);
        showCenterSwal({
            title: "Tes Alarm Pengawas",
            text: "Suara sirine 3,6 detik dan sistem notifikasi kecurangan siap digunakan.",
            icon: "info",
            timer: 3500
        });
    };

    // Firebase Setup
    let fbDb = null;
    function setConnectionStatus(state, text) {
        const badge = document.getElementById("firebaseConnBadge");
        const label = document.getElementById("firebaseConnText");
        if (!badge || !label) return;
        badge.className = "conn-pill " + state;
        label.innerText = text;
    }

    try {
        if (window.firebase) {
            if (!firebase.apps.length) {
                firebase.initializeApp({ databaseURL: firebaseDbUrl });
            }
            fbDb = firebase.database();

            fbDb.ref(".info/connected").on("value", (snap) => {
                setConnectionStatus(snap.val() === true ? "conn-online" : "conn-connecting",
                    snap.val() === true ? "Firebase Live" : "Menghubungkan...");
            });

            fbDb.ref("settings/adminAuth").on("value", (snap) => {
                const val = snap.val();
                if (val && val.password) adminPasswordSetting = String(val.password);
            });

            fbDb.ref("classes").on("value", (snap) => {
                const remote = snap.val();
                if (!remote) {
                    // Seed default classes once
                    const seed = {};
                    DEFAULT_CLASSES.forEach(c => { seed[c.id] = { ...c, createdAt: Date.now() }; });
                    classesMap = seed;
                    fbDb.ref("classes").set(seed);
                } else {
                    classesMap = remote;
                }
                refreshClassFilterOptions();
                renderClassesManager();
                renderTeacherClassesCheckboxes();
            });

            fbDb.ref("teachers").on("value", (snap) => {
                teachersMap = snap.val() || {};
                // Live sync updated class permissions for logged-in teacher
                if (currentUser && currentUser.role === "TEACHER") {
                    const updated = teachersMap[currentUser.id];
                    if (!updated || updated.active === false) {
                        logoutDashboard();
                        return;
                    }
                    currentUser.assignedClasses = Array.isArray(updated.assignedClasses) ? updated.assignedClasses : [];
                    currentUser.name = updated.name;
                    localStorage.setItem("robbani_auth_session", JSON.stringify(currentUser));
                    applyRoleUI();
                }
                renderTeachersTable();
                updateAllUI();
            });

            fbDb.ref("students").on("value", (snapshot) => {
                const raw = snapshot.val() || {};
                const normalized = {};
                Object.entries(raw).forEach(([fbKey, val]) => {
                    if (val && typeof val === "object") {
                        normalized[fbKey] = { ...val, _fbKey: fbKey, studentId: val.studentId || fbKey };
                    }
                });
                studentsStatusMap = normalized;
                checkViolationsAndTriggerAlerts();
                refreshClassFilterOptions();
                updateAllUI();
            });

            fbDb.ref("exams").on("value", (snapshot) => {
                const remoteExams = snapshot.val();
                if (remoteExams && typeof remoteExams === "object") {
                    examsMap = remoteExams;
                    saveExamsLocal();
                    refreshClassFilterOptions();
                    updateAllUI();
                }
            });

            fbDb.ref("settings/announcement").on("value", (snapshot) => {
                const val = snapshot.val();
                const input = document.getElementById("inputBroadcastMessage");
                if (input && val && typeof val.message === "string") {
                    input.value = val.message;
                }
            });
        } else {
            setConnectionStatus("conn-offline", "SDK Offline");
        }
    } catch (err) {
        console.warn("Firebase Error:", err);
        setConnectionStatus("conn-offline", "Gagal Terhubung");
    }

    // --- Role & Class Access Control ---
    function getAllClassesList() {
        return Object.values(classesMap).sort((a, b) => String(a.name).localeCompare(String(b.name)));
    }

    function getAllowedClasses() {
        if (!currentUser) return [];
        if (currentUser.role === "ADMIN") {
            return getAllClassesList().map(c => c.name);
        }
        return Array.isArray(currentUser.assignedClasses) ? currentUser.assignedClasses : [];
    }

    function isClassAllowed(className) {
        if (!currentUser) return false;
        if (currentUser.role === "ADMIN") return true;
        const target = String(className || "").trim().toLowerCase();
        return getAllowedClasses().some(c => String(c).trim().toLowerCase() === target);
    }

    function isExamAllowed(exam) {
        if (!currentUser) return false;
        if (currentUser.role === "ADMIN") return true;
        if (exam.teacherUsername && exam.teacherUsername === currentUser.username) return true;
        const targets = Array.isArray(exam.targetClasses) ? exam.targetClasses : [];
        return targets.some(c => isClassAllowed(c));
    }

    function applyRoleUI() {
        const gate = document.getElementById("loginGate");
        const shell = document.getElementById("dashboardShell");
        if (!currentUser) {
            gate.classList.remove("d-none");
            shell.classList.add("d-none");
            return;
        }
        gate.classList.add("d-none");
        shell.classList.remove("d-none");

        const isAdmin = currentUser.role === "ADMIN";
        document.getElementById("navTabAccess").classList.toggle("d-none", !isAdmin);
        document.getElementById("btnServerConfig").classList.toggle("d-none", !isAdmin);
        document.getElementById("broadcastSection").classList.toggle("d-none", !isAdmin);

        const sub = document.getElementById("topbarUserSubtitle");
        const banner = document.getElementById("allowedClassesBanner");
        if (isAdmin) {
            sub.innerText = "Login sebagai: Super Admin • Akses Penuh Semua Kelas";
            banner.innerText = `Total Kelas Terdaftar: ${getAllClassesList().length}`;
        } else {
            const clsList = getAllowedClasses();
            sub.innerText = `Guru: ${currentUser.name} (${currentUser.username})`;
            banner.innerText = `Akses Kelas Anda: ${clsList.length > 0 ? clsList.join(", ") : "Belum ada kelas ditugaskan"}`;
        }

        if (!isAdmin) switchTab("monitoring");
        refreshClassFilterOptions();
        updateAllUI();
    }

    window.switchTab = function (tab) {
        const mon = document.getElementById("panelMonitoring");
        const acc = document.getElementById("panelAccessControl");
        const btnMon = document.getElementById("navTabMonitoring");
        const btnAcc = document.getElementById("navTabAccess");

        if (tab === "access" && currentUser?.role === "ADMIN") {
            mon.classList.add("d-none");
            acc.classList.remove("d-none");
            btnMon.classList.remove("active");
            btnAcc.classList.add("active");
        } else {
            acc.classList.add("d-none");
            mon.classList.remove("d-none");
            btnAcc.classList.remove("active");
            btnMon.classList.add("active");
        }
    };

    document.getElementById("formLogin")?.addEventListener("submit", function (e) {
        e.preventDefault();
        const u = document.getElementById("loginUsername").value.trim();
        const p = document.getElementById("loginPassword").value;

        if (u.toLowerCase() === "admin" && p === adminPasswordSetting) {
            currentUser = { id: "ADMIN", role: "ADMIN", username: "admin", name: "Super Admin" };
            localStorage.setItem("robbani_auth_session", JSON.stringify(currentUser));
            applyRoleUI();
            return;
        }

        const matchedTeacher = Object.entries(teachersMap).find(([, t]) =>
            t && t.active !== false &&
            String(t.username || "").toLowerCase() === u.toLowerCase() &&
            String(t.password || "") === p
        );

        if (matchedTeacher) {
            const [tid, t] = matchedTeacher;
            currentUser = {
                id: tid,
                role: "TEACHER",
                username: t.username,
                name: t.name,
                assignedClasses: Array.isArray(t.assignedClasses) ? t.assignedClasses : []
            };
            localStorage.setItem("robbani_auth_session", JSON.stringify(currentUser));
            applyRoleUI();
        } else {
            showCenterSwal({
                title: "Login Gagal",
                text: "Username atau password salah, atau akun guru belum aktif.",
                icon: "error",
                timer: 2600
            });
        }
    });

    window.logoutDashboard = function () {
        currentUser = null;
        localStorage.removeItem("robbani_auth_session");
        document.getElementById("formLogin")?.reset();
        applyRoleUI();
    };

    // --- Class & Teacher Management (Admin Only) ---
    document.getElementById("formAddClass")?.addEventListener("submit", function (e) {
        e.preventDefault();
        if (currentUser?.role !== "ADMIN" || !fbDb) return;
        const name = document.getElementById("newClassName").value.trim();
        const level = document.getElementById("newClassLevel").value;
        if (!name) return;

        const cid = sanitizeKey(name.toUpperCase());
        const obj = { id: cid, name, level, createdAt: Date.now() };
        classesMap[cid] = obj;
        fbDb.ref(`classes/${cid}`).set(obj);
        document.getElementById("newClassName").value = "";
        showCenterSwal({ title: "Kelas Ditambahkan", text: `Kelas ${name} (${level}) siap digunakan.`, icon: "success", timer: 1800 });
    });

    window.deleteClassItem = function (cid, className) {
        if (currentUser?.role !== "ADMIN" || !fbDb) return;
        Swal.fire({
            title: `Hapus Kelas ${className}?`,
            text: "Kelas akan dihapus dari daftar master.",
            icon: "warning",
            width: "32rem",
            showCancelButton: true,
            confirmButtonColor: "#dc2626",
            confirmButtonText: "Hapus",
            cancelButtonText: "Batal",
            customClass: { popup: "robbani-swal-wide" }
        }).then(res => {
            if (res.isConfirmed) {
                delete classesMap[cid];
                fbDb.ref(`classes/${cid}`).remove();
            }
        });
    };

    function renderClassesManager() {
        const container = document.getElementById("classesListContainer");
        if (!container) return;
        const list = getAllClassesList();
        if (list.length === 0) {
            container.innerHTML = `<span class="text-muted small">Belum ada kelas terdaftar.</span>`;
            return;
        }
        container.innerHTML = list.map(c => `
            <div class="class-badge-item">
                <span>${escapeHtml(c.name)}</span>
                <span class="badge bg-primary-subtle text-primary">${escapeHtml(c.level || "SMP")}</span>
                <button type="button" class="btn btn-sm p-0 text-danger border-0" onclick="deleteClassItem('${escapeHtml(c.id)}', '${escapeHtml(c.name)}')" title="Hapus kelas">
                    <i class="fas fa-xmark"></i>
                </button>
            </div>
        `).join("");
    }

    function renderTeacherClassesCheckboxes(selected = []) {
        const container = document.getElementById("teacherClassesCheckboxes");
        if (!container) return;
        const list = getAllClassesList();
        container.innerHTML = list.map(c => `
            <label class="class-chip">
                <input type="checkbox" value="${escapeHtml(c.name)}" ${selected.includes(c.name) ? "checked" : ""}>
                <span>${escapeHtml(c.name)}</span>
            </label>
        `).join("");
    }

    window.prepareTeacherModal = function () {
        document.getElementById("editingTeacherId").value = "";
        document.getElementById("teacherModalTitle").innerHTML = '<i class="fas fa-user-plus me-2"></i>Tambah Guru &amp; Hak Akses';
        document.getElementById("formTeacher").reset();
        renderTeacherClassesCheckboxes([]);
        new bootstrap.Modal(document.getElementById("modalTeacher")).show();
    };

    window.editTeacher = function (tid) {
        const t = teachersMap[tid];
        if (!t) return;
        document.getElementById("editingTeacherId").value = tid;
        document.getElementById("teacherModalTitle").innerHTML = `<i class="fas fa-pen me-2"></i>Edit Guru: ${escapeHtml(t.name)}`;
        document.getElementById("teacherFullName").value = t.name || "";
        document.getElementById("teacherUsername").value = t.username || "";
        document.getElementById("teacherPassword").value = t.password || "";
        renderTeacherClassesCheckboxes(Array.isArray(t.assignedClasses) ? t.assignedClasses : []);
        new bootstrap.Modal(document.getElementById("modalTeacher")).show();
    };

    document.getElementById("formTeacher")?.addEventListener("submit", function (e) {
        e.preventDefault();
        if (currentUser?.role !== "ADMIN" || !fbDb) return;
        const existingId = document.getElementById("editingTeacherId").value.trim();
        const name = document.getElementById("teacherFullName").value.trim();
        const username = document.getElementById("teacherUsername").value.trim();
        const password = document.getElementById("teacherPassword").value.trim();
        const assignedClasses = Array.from(document.querySelectorAll("#teacherClassesCheckboxes input:checked")).map(cb => cb.value);

        if (username.toLowerCase() === "admin") {
            showCenterSwal({ title: "Username Tidak Valid", text: "Username 'admin' khusus untuk Super Admin.", icon: "error", timer: 2400 });
            return;
        }

        const tid = existingId || ("TCH-" + Date.now());
        const obj = { id: tid, name, username, password, assignedClasses, active: true, createdAt: Date.now() };
        teachersMap[tid] = obj;
        fbDb.ref(`teachers/${tid}`).set(obj);

        const modal = bootstrap.Modal.getInstance(document.getElementById("modalTeacher"));
        if (modal) modal.hide();
        showCenterSwal({
            title: "Data Guru Disimpan",
            html: `<strong>${escapeHtml(name)}</strong> memiliki akses ke kelas: <b>${escapeHtml(assignedClasses.join(", ") || "Belum dipilih")}</b>`,
            icon: "success",
            timer: 2500
        });
    });

    window.deleteTeacher = function (tid, name) {
        if (currentUser?.role !== "ADMIN" || !fbDb) return;
        Swal.fire({
            title: `Hapus Akun ${name}?`,
            icon: "warning",
            width: "32rem",
            showCancelButton: true,
            confirmButtonColor: "#dc2626",
            confirmButtonText: "Hapus",
            cancelButtonText: "Batal",
            customClass: { popup: "robbani-swal-wide" }
        }).then(res => {
            if (res.isConfirmed) {
                delete teachersMap[tid];
                fbDb.ref(`teachers/${tid}`).remove();
            }
        });
    };

    function renderTeachersTable() {
        const tbody = document.getElementById("teachersTableBody");
        if (!tbody) return;
        const list = Object.entries(teachersMap);
        if (list.length === 0) {
            tbody.innerHTML = `<tr><td colspan="4" class="text-center py-4 text-muted">Belum ada akun guru. Klik "Tambah Guru" untuk membuat akses per kelas.</td></tr>`;
            return;
        }
        tbody.innerHTML = list.map(([tid, t]) => {
            const cls = Array.isArray(t.assignedClasses) ? t.assignedClasses : [];
            return `
                <tr>
                    <td class="fw-bold">${escapeHtml(t.name)}</td>
                    <td><span class="font-mono small">${escapeHtml(t.username)}</span></td>
                    <td>${cls.length ? cls.map(c => `<span class="badge bg-primary-subtle text-primary me-1">${escapeHtml(c)}</span>`).join("") : '<span class="text-muted small">Tidak ada kelas</span>'}</td>
                    <td class="text-end text-nowrap">
                        <button class="btn btn-sm btn-outline-secondary rounded-pill me-1" onclick="editTeacher('${escapeHtml(tid)}')"><i class="fas fa-pen"></i></button>
                        <button class="btn btn-sm btn-outline-danger rounded-pill" onclick="deleteTeacher('${escapeHtml(tid)}', '${escapeHtml(t.name)}')"><i class="fas fa-trash"></i></button>
                    </td>
                </tr>`;
        }).join("");
    }

    // --- Filtered Exams & Students ---
    function getExamsArray() {
        return Object.values(examsMap)
            .filter(isExamAllowed)
            .sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
    }

    function getVisibleStudents() {
        return Object.values(studentsStatusMap)
            .filter(s => isClassAllowed(s.className))
            .sort((a, b) => (b.lastUpdated || 0) - (a.lastUpdated || 0));
    }

    function saveExamsLocal() {
        localStorage.setItem("robbani_exams", JSON.stringify(Object.values(examsMap)));
    }

    function checkViolationsAndTriggerAlerts() {
        if (!currentUser) return;
        getVisibleStudents().forEach(s => {
            const vCount = parseInt(s.violationCount) || 0;
            const fbKey = s._fbKey || s.studentId;
            if (!fbKey) return;
            const exam = examsMap[s.examCode];
            const maxAllowed = (exam && exam.maxViolations) ? parseInt(exam.maxViolations) : 3;

            if ((vCount >= maxAllowed || s.status === "TERKUNCI") && !alertedViolationsMap[fbKey]) {
                alertedViolationsMap[fbKey] = true;
                playAlarmSound(false);

                if (window.Swal) {
                    Swal.fire({
                        title: "PERINGATAN KECURANGAN SISWA!",
                        html: `<div class="text-start p-2">
                                <p class="mb-1"><strong>Nama:</strong> ${escapeHtml(s.studentName || s.name || "Siswa")}</p>
                                <p class="mb-1"><strong>Kelas:</strong> ${escapeHtml(s.className || "-")}</p>
                                <p class="mb-1"><strong>Kode Ujian:</strong> ${escapeHtml(s.examCode || "-")}</p>
                                <p class="mb-1 text-danger fw-bold"><strong>Pelanggaran:</strong> ${vCount} / ${maxAllowed}</p>
                                <p class="mb-0 text-muted"><strong>Catatan:</strong> ${escapeHtml(s.lastViolationReason || "Melebihi batas pelanggaran")}</p>
                               </div>
                               <div class="alert alert-danger mt-3 mb-0 small">
                                 <i class="fas fa-lock me-1"></i> Layar HP siswa TERKUNCI dan alarm berbunyi!
                               </div>`,
                        icon: "warning",
                        width: "38rem",
                        position: "center",
                        confirmButtonText: '<i class="fas fa-unlock me-1"></i> Buka Kunci Jarak Jauh',
                        showCancelButton: true,
                        cancelButtonText: "Tutup",
                        confirmButtonColor: "#2563eb",
                        cancelButtonColor: "#64748b",
                        customClass: { popup: "robbani-swal-wide" }
                    }).then((result) => {
                        if (result.isConfirmed) {
                            resetViolation(fbKey);
                        }
                    });
                }
            }
        });
    }

    function refreshClassFilterOptions() {
        const select = document.getElementById("filterClass");
        if (!select) return;
        const currentVal = select.value;
        const allowed = new Set(getAllowedClasses());
        if (currentUser?.role === "ADMIN") {
            Object.values(studentsStatusMap).forEach(s => { if (s.className) allowed.add(s.className); });
        }
        const sorted = Array.from(allowed).sort();
        select.innerHTML = `<option value="ALL">Semua Kelas Akses</option>` +
            sorted.map(c => `<option value="${escapeHtml(c)}">${escapeHtml(c)}</option>`).join("");
        if ([...select.options].some(o => o.value === currentVal)) {
            select.value = currentVal;
        }
    }

    function updateStatCards() {
        const exams = getExamsArray();
        const studentList = getVisibleStudents();

        const activeStudents = studentList.filter(s =>
            s.status === "SEDANG UJIAN" || (s.status && s.status.startsWith("PERINGATAN"))
        ).length;
        const totalViolations = studentList.reduce((sum, s) => sum + (parseInt(s.violationCount) || 0), 0);
        const lockedStudents = studentList.filter(s => s.status === "TERKUNCI").length;

        document.getElementById("statActiveExams").innerText = exams.length;
        document.getElementById("statActiveStudents").innerText = activeStudents;
        document.getElementById("statTotalViolations").innerText = totalViolations;
        document.getElementById("statLockedStudents").innerText = lockedStudents;
    }

    function renderExamsList() {
        const container = document.getElementById("examsContainer");
        if (!container) return;
        const exams = getExamsArray();

        if (exams.length === 0) {
            container.innerHTML = `
                <div class="col-12">
                    <div class="panel-card text-center p-5">
                        <i class="fas fa-folder-plus fa-3x text-primary opacity-50 mb-3"></i>
                        <h5 class="fw-bold mb-1">Belum Ada Ujian pada Kelas Akses Anda</h5>
                        <p class="text-muted small mb-3">Klik <strong>Buat Ujian</strong> untuk mendaftarkan link Moodle dan menghasilkan QR Code siswa.</p>
                        <button class="btn btn-primary btn-sm rounded-pill px-4" data-bs-toggle="modal" data-bs-target="#modalCreateExam" onclick="prepareCreateExamModal()">
                            <i class="fas fa-plus me-1"></i> Buat Ujian Pertama
                        </button>
                    </div>
                </div>`;
            return;
        }

        container.innerHTML = exams.map(exam => {
            const classesLabel = Array.isArray(exam.targetClasses) ? exam.targetClasses.join(", ") : "Semua Kelas";
            return `
                <div class="col-md-6 col-xl-4">
                    <div class="exam-card">
                        <div class="d-flex justify-content-between align-items-center mb-2">
                            <span class="badge bg-primary-subtle text-primary rounded-pill px-3 py-1 fw-semibold">${escapeHtml(exam.subject || "Ujian")}</span>
                            <span class="code-pill" onclick="copyExamCode('${escapeHtml(exam.examCode)}')" title="Klik untuk salin kode">
                                ${escapeHtml(exam.examCode)} <i class="fas fa-copy ms-1 small"></i>
                            </span>
                        </div>
                        <h6 class="fw-800 text-dark mb-2">${escapeHtml(exam.title)}</h6>
                        <div class="small text-muted mb-1"><i class="fas fa-chalkboard-user me-2 text-primary"></i>${escapeHtml(exam.teacherName || "-")}</div>
                        <div class="small text-muted mb-1"><i class="fas fa-users me-2 text-primary"></i>Kelas: ${escapeHtml(classesLabel)}</div>
                        <div class="small text-muted mb-2"><i class="fas fa-stopwatch me-2 text-primary"></i>${exam.durationMinutes || 90} menit • Maks ${exam.maxViolations || 3}x • PIN: <span class="font-mono fw-bold">${escapeHtml(exam.adminPin || "123456")}</span></div>
                        <div class="small bg-light p-2 rounded-3 text-break mb-3 border">
                            <i class="fas fa-link me-1 text-primary"></i>
                            <a href="${escapeHtml(exam.moodleUrl)}" target="_blank" rel="noopener" class="text-decoration-none">${escapeHtml(exam.moodleUrl)}</a>
                        </div>
                        <div class="mt-auto d-flex gap-2">
                            <button class="btn btn-primary btn-sm rounded-pill flex-fill fw-semibold" onclick="showQrModal('${escapeHtml(exam.examCode)}')">
                                <i class="fas fa-qrcode me-1"></i> QR &amp; Kartu
                            </button>
                            <button class="btn btn-outline-secondary btn-sm rounded-pill px-3" onclick="editExam('${escapeHtml(exam.examCode)}')" title="Edit Ujian">
                                <i class="fas fa-pen"></i>
                            </button>
                            <button class="btn btn-outline-danger btn-sm rounded-pill px-3" onclick="deleteExam('${escapeHtml(exam.examCode)}')" title="Hapus Ujian">
                                <i class="fas fa-trash"></i>
                            </button>
                        </div>
                    </div>
                </div>`;
        }).join("");
    }

    function formatTime(seconds) {
        const sNum = parseInt(seconds) || 0;
        if (sNum <= 0) return "-";
        const h = Math.floor(sNum / 3600);
        const m = Math.floor((sNum % 3600) / 60);
        const s = sNum % 60;
        if (h > 0) return `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
        return `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
    }

    function getInitials(name) {
        const parts = String(name || "S").trim().split(/\s+/);
        if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase();
        return (parts[0] || "S").slice(0, 2).toUpperCase();
    }

    function renderStudentsTable() {
        const tbody = document.getElementById("studentsTableBody");
        if (!tbody) return;

        const classFilter = document.getElementById("filterClass")?.value || "ALL";
        const statusFilter = document.getElementById("filterStatus")?.value || "ALL";
        const searchQuery = (document.getElementById("searchStudent")?.value || "").trim().toLowerCase();

        const filtered = getVisibleStudents().filter(s => {
            if (classFilter !== "ALL" && s.className !== classFilter) return false;
            const st = String(s.status || "").toUpperCase();
            if (statusFilter === "ACTIVE" && st !== "SEDANG UJIAN") return false;
            if (statusFilter === "WARNING" && !st.startsWith("PERINGATAN")) return false;
            if (statusFilter === "LOCKED" && st !== "TERKUNCI") return false;
            if (statusFilter === "FINISHED" && st !== "SELESAI") return false;
            if (searchQuery) {
                const hay = `${s.studentName || ""} ${s.nis || ""} ${s.examCode || ""} ${s.className || ""}`.toLowerCase();
                if (!hay.includes(searchQuery)) return false;
            }
            return true;
        });

        if (filtered.length === 0) {
            tbody.innerHTML = `
                <tr>
                    <td colspan="8" class="text-center py-5 text-muted">
                        <i class="fas fa-user-clock fa-2x mb-2 opacity-50 d-block"></i>
                        Belum ada peserta ujian pada kelas akses Anda.
                    </td>
                </tr>`;
            return;
        }

        tbody.innerHTML = filtered.map(s => {
            const fbKey = s._fbKey || s.studentId || "";
            const name = s.studentName || s.name || "Siswa";
            const vCount = parseInt(s.violationCount) || 0;
            const exam = examsMap[s.examCode];
            const maxAllowed = (exam && exam.maxViolations) ? parseInt(exam.maxViolations) : 3;
            const st = String(s.status || "SEDANG UJIAN");

            let pillClass = "status-active";
            if (st === "SELESAI") pillClass = "status-finished";
            else if (st === "TERKUNCI" || vCount >= maxAllowed) pillClass = "status-locked";
            else if (vCount > 0 || st.startsWith("PERINGATAN")) pillClass = "status-warning";

            const lastTime = s.lastUpdated ? new Date(s.lastUpdated).toLocaleTimeString("id-ID", { hour: "2-digit", minute: "2-digit", second: "2-digit" }) : "";

            return `
                <tr>
                    <td>
                        <div class="d-flex align-items-center gap-2">
                            <div class="avatar-circle">${escapeHtml(getInitials(name))}</div>
                            <div>
                                <div class="fw-bold text-dark">${escapeHtml(name)}</div>
                                <div class="text-muted" style="font-size:0.75rem;">${s.nis ? "NIS: " + escapeHtml(s.nis) : escapeHtml(fbKey)}</div>
                            </div>
                        </div>
                    </td>
                    <td><span class="badge bg-light text-dark border fw-semibold">${escapeHtml(s.className || "-")}</span></td>
                    <td><span class="font-mono small fw-bold text-primary">${escapeHtml(s.examCode || "-")}</span></td>
                    <td><span class="status-pill ${pillClass}">${escapeHtml(st)}</span></td>
                    <td class="text-center">
                        <span class="fw-800 ${vCount > 0 ? "text-danger" : "text-success"}">${vCount} / ${maxAllowed}</span>
                    </td>
                    <td>
                        <div class="small text-dark">${escapeHtml(s.lastViolationReason || "-")}</div>
                        <div class="text-muted" style="font-size:0.72rem;">${lastTime ? "Update " + lastTime : ""}</div>
                    </td>
                    <td class="font-mono fw-bold text-primary">${formatTime(s.remainingSeconds)}</td>
                    <td class="text-end text-nowrap">
                        <button class="btn btn-sm btn-outline-primary rounded-pill me-1" onclick="resetViolation('${escapeHtml(fbKey)}')" title="Buka Kunci Jarak Jauh">
                            <i class="fas fa-unlock"></i>
                        </button>
                        <button class="btn btn-sm btn-outline-info rounded-pill me-1" onclick="sendMessageToStudent('${escapeHtml(fbKey)}', '${escapeHtml(name)}')" title="Kirim Pesan ke HP Siswa">
                            <i class="fas fa-comment-dots"></i>
                        </button>
                        <button class="btn btn-sm btn-outline-secondary rounded-pill me-1" onclick="showStudentLogs('${escapeHtml(fbKey)}')" title="Riwayat Pelanggaran">
                            <i class="fas fa-list-ul"></i>
                        </button>
                        <button class="btn btn-sm btn-outline-warning rounded-pill me-1" onclick="forceFinishStudent('${escapeHtml(fbKey)}', '${escapeHtml(name)}')" title="Akhiri Ujian Paksa">
                            <i class="fas fa-stop"></i>
                        </button>
                        <button class="btn btn-sm btn-outline-danger rounded-pill" onclick="removeStudentSession('${escapeHtml(fbKey)}')" title="Hapus Baris">
                            <i class="fas fa-trash"></i>
                        </button>
                    </td>
                </tr>`;
        }).join("");
    }

    function updateAllUI() {
        updateStatCards();
        renderExamsList();
        renderStudentsTable();
    }

    function renderExamTargetClassesCheckboxes(selected = []) {
        const container = document.getElementById("examClasses");
        if (!container) return;
        const allowed = getAllowedClasses();
        if (allowed.length === 0) {
            container.innerHTML = `<span class="text-danger small">Belum ada kelas yang ditugaskan untuk akun Anda. Hubungi Admin.</span>`;
            return;
        }
        container.innerHTML = allowed.map((cls, idx) => {
            const isChecked = selected.length > 0 ? selected.includes(cls) : idx === 0;
            return `<label class="class-chip"><input type="checkbox" value="${escapeHtml(cls)}" ${isChecked ? "checked" : ""}><span>${escapeHtml(cls)}</span></label>`;
        }).join("");
    }

    window.prepareCreateExamModal = function () {
        document.getElementById("editingExamCode").value = "";
        document.getElementById("examModalTitle").innerHTML = '<i class="fas fa-plus-circle me-2"></i>Buat Sesi Ujian Moodle';
        document.getElementById("formCreateExam").reset();
        document.getElementById("examDuration").value = 90;
        document.getElementById("examMaxViolations").value = 3;
        document.getElementById("examPin").value = "123456";
        if (currentUser && currentUser.role === "TEACHER") {
            document.getElementById("examTeacher").value = currentUser.name;
        }
        renderExamTargetClassesCheckboxes([]);
    };

    window.editExam = function (examCode) {
        const exam = examsMap[examCode];
        if (!exam || !isExamAllowed(exam)) return;
        document.getElementById("editingExamCode").value = exam.examCode;
        document.getElementById("examModalTitle").innerHTML = '<i class="fas fa-pen me-2"></i>Edit Ujian: ' + escapeHtml(exam.examCode);
        document.getElementById("examTitle").value = exam.title || "";
        document.getElementById("examSubject").value = exam.subject || "";
        document.getElementById("examTeacher").value = exam.teacherName || "";
        document.getElementById("examToken").value = exam.token || "";
        document.getElementById("examMoodleUrl").value = exam.moodleUrl || "";
        document.getElementById("examDuration").value = exam.durationMinutes || 90;
        document.getElementById("examMaxViolations").value = exam.maxViolations || 3;
        document.getElementById("examPin").value = exam.adminPin || "123456";
        renderExamTargetClassesCheckboxes(Array.isArray(exam.targetClasses) ? exam.targetClasses : []);
        new bootstrap.Modal(document.getElementById("modalCreateExam")).show();
    };

    document.getElementById("formCreateExam")?.addEventListener("submit", function (e) {
        e.preventDefault();
        const existingCode = document.getElementById("editingExamCode").value.trim();
        const examCode = existingCode || ("RBN" + Math.floor(1000 + Math.random() * 9000));
        const selectedClasses = Array.from(document.querySelectorAll("#examClasses input:checked")).map(cb => cb.value);

        if (selectedClasses.length === 0) {
            showCenterSwal({ title: "Pilih Target Kelas", text: "Centang minimal 1 kelas untuk ujian ini.", icon: "warning", timer: 2200 });
            return;
        }

        const examObj = {
            id: existingCode ? (examsMap[existingCode]?.id || "EXAM-" + Date.now()) : "EXAM-" + Date.now(),
            examCode: examCode,
            title: document.getElementById("examTitle").value.trim(),
            subject: document.getElementById("examSubject").value.trim(),
            teacherName: document.getElementById("examTeacher").value.trim(),
            teacherUsername: existingCode ? (examsMap[existingCode]?.teacherUsername || currentUser?.username || "") : (currentUser?.username || ""),
            targetClasses: selectedClasses,
            moodleUrl: document.getElementById("examMoodleUrl").value.trim(),
            token: document.getElementById("examToken").value.trim(),
            durationMinutes: parseInt(document.getElementById("examDuration").value) || 90,
            maxViolations: parseInt(document.getElementById("examMaxViolations").value) || 3,
            adminPin: document.getElementById("examPin").value.trim() || "123456",
            createdAt: existingCode ? (examsMap[existingCode]?.createdAt || Date.now()) : Date.now()
        };

        examsMap[examCode] = examObj;
        saveExamsLocal();
        if (fbDb) fbDb.ref(`exams/${examCode}`).set(examObj);

        const modal = bootstrap.Modal.getInstance(document.getElementById("modalCreateExam"));
        if (modal) modal.hide();
        updateAllUI();
        showQrModal(examCode);
    });

    window.showQrModal = function (examCode) {
        const exam = examsMap[examCode];
        if (!exam) return;
        currentQrExamCode = exam.examCode;

        document.getElementById("qrModalTitle").innerText = exam.title;
        document.getElementById("qrExamCodeDisplay").innerText = exam.examCode;
        document.getElementById("qrExamPinDisplay").innerText = exam.adminPin;
        document.getElementById("qrExamMaxViolationsDisplay").innerText = (exam.maxViolations || 3) + "x";
        document.getElementById("qrMoodleUrlDisplay").innerText = exam.moodleUrl;

        const qrContainer = document.getElementById("qrCodeCanvas");
        qrContainer.innerHTML = "";
        const payload = JSON.stringify({
            examCode: exam.examCode,
            title: exam.title,
            subject: exam.subject || "",
            teacherName: exam.teacherName || "",
            moodleUrl: exam.moodleUrl,
            token: exam.token || "",
            durationMinutes: exam.durationMinutes || 90,
            maxViolations: exam.maxViolations || 3,
            adminPin: exam.adminPin || "123456"
        });
        if (window.QRCode) new QRCode(qrContainer, { text: payload, width: 210, height: 210 });
        new bootstrap.Modal(document.getElementById("modalQrDisplay")).show();
    };

    window.copyExamCode = function (code) {
        navigator.clipboard?.writeText(code);
        showCenterSwal({
            title: "Kode Ujian Disalin",
            html: `<div class="fs-5 fw-bold text-primary font-mono">${escapeHtml(code)}</div><p class="text-muted small mb-0 mt-1">Bagikan kode ini kepada siswa untuk masuk ujian.</p>`,
            icon: "success",
            timer: 1800
        });
    };

    window.copyCurrentQrCode = function () {
        if (currentQrExamCode) copyExamCode(currentQrExamCode);
    };

    window.deleteExam = function (examCode) {
        const exam = examsMap[examCode];
        if (!exam || !isExamAllowed(exam)) return;
        Swal.fire({
            title: `Hapus Ujian ${examCode}?`,
            text: "Ujian akan dihapus dari daftar dan Firebase.",
            icon: "warning",
            width: "34rem",
            position: "center",
            showCancelButton: true,
            confirmButtonColor: "#dc2626",
            confirmButtonText: "Ya, Hapus",
            cancelButtonText: "Batal",
            customClass: { popup: "robbani-swal-wide" }
        }).then(res => {
            if (res.isConfirmed) {
                delete examsMap[examCode];
                saveExamsLocal();
                if (fbDb) fbDb.ref(`exams/${examCode}`).remove();
                updateAllUI();
            }
        });
    };

    // Remote Student Actions
    window.resetViolation = function (fbKey) {
        if (!fbDb || !fbKey) return;
        delete alertedViolationsMap[fbKey];
        fbDb.ref(`students/${fbKey}`).update({
            status: "SEDANG UJIAN",
            violationCount: 0,
            lastViolationReason: "Buka Kunci Jarak Jauh Pengawas",
            remoteCommand: "UNLOCK",
            remoteMessage: ""
        });
        showCenterSwal({
            title: "Perintah Buka Kunci Terkirim",
            text: "Layar ujian di HP siswa akan langsung terbuka secara real-time.",
            icon: "success",
            timer: 2000
        });
    };

    window.sendMessageToStudent = function (fbKey, studentName) {
        if (!fbDb || !fbKey || !window.Swal) return;
        Swal.fire({
            title: `Kirim Notifikasi ke ${studentName}`,
            html: `<p class="text-muted small mb-2">Pesan ini akan langsung muncul di tengah layar HP siswa beserta bunyi peringatan.</p>`,
            input: "textarea",
            inputPlaceholder: "Contoh: Harap fokus ke layar ujian!",
            width: "38rem",
            position: "center",
            showCancelButton: true,
            confirmButtonText: '<i class="fas fa-paper-plane me-1"></i> Kirim Sekarang',
            cancelButtonText: "Batal",
            confirmButtonColor: "#2563eb",
            customClass: { popup: "robbani-swal-wide" }
        }).then(result => {
            if (result.isConfirmed && result.value && result.value.trim()) {
                const msgText = result.value.trim();
                fbDb.ref(`students/${fbKey}`).update({
                    remoteCommand: "MESSAGE",
                    remoteMessage: msgText
                });
                showCenterSwal({
                    title: `Notifikasi Terkirim ke ${studentName}`,
                    html: `<div class="p-3 bg-light rounded-3 border text-dark fw-semibold">${escapeHtml(msgText)}</div>`,
                    icon: "success",
                    timer: 2200
                });
            }
        });
    };

    window.forceFinishStudent = function (fbKey, studentName) {
        if (!fbDb || !fbKey) return;
        Swal.fire({
            title: `Akhiri Ujian ${studentName}?`,
            text: "Aplikasi di HP siswa akan langsung menutup sesi ujian.",
            icon: "warning",
            width: "36rem",
            position: "center",
            showCancelButton: true,
            confirmButtonColor: "#d97706",
            confirmButtonText: "Akhiri Sekarang",
            cancelButtonText: "Batal",
            customClass: { popup: "robbani-swal-wide" }
        }).then(res => {
            if (res.isConfirmed) {
                fbDb.ref(`students/${fbKey}`).update({
                    status: "SELESAI",
                    remoteCommand: "FORCE_FINISH",
                    lastViolationReason: "Diakhiri paksa oleh Pengawas"
                });
                showCenterSwal({
                    title: "Perintah Akhiri Ujian Terkirim",
                    text: `Sesi ujian ${studentName} telah dihentikan dari jarak jauh.`,
                    icon: "info",
                    timer: 2000
                });
            }
        });
    };

    window.showStudentLogs = function (fbKey) {
        const s = studentsStatusMap[fbKey];
        const body = document.getElementById("violationLogsBody");
        if (!s || !body) return;

        const logs = Array.isArray(s.violationLogs) ? s.violationLogs : [];
        if (logs.length === 0) {
            body.innerHTML = `<div class="text-center text-muted py-4"><i class="fas fa-check-circle fa-2x text-success mb-2 d-block"></i>Belum ada catatan pelanggaran untuk ${escapeHtml(s.studentName || "siswa ini")}.</div>`;
        } else {
            body.innerHTML = `<div class="mb-2 fw-bold">${escapeHtml(s.studentName)} (${escapeHtml(s.className)})</div>` +
                logs.slice().reverse().map(log => {
                    const t = log.timestamp ? new Date(log.timestamp).toLocaleTimeString("id-ID") : "-";
                    return `<div class="p-2 mb-2 rounded-3 bg-light border small">
                                <div class="d-flex justify-content-between fw-bold text-danger">
                                    <span>${escapeHtml(log.violationType || "Pelanggaran")}</span>
                                    <span class="font-mono text-muted">${t}</span>
                                </div>
                                <div class="text-muted">${escapeHtml(log.details || "-")}</div>
                            </div>`;
                }).join("");
        }
        new bootstrap.Modal(document.getElementById("modalViolationLogs")).show();
    };

    window.removeStudentSession = function (fbKey) {
        if (fbDb && fbKey) {
            delete alertedViolationsMap[fbKey];
            fbDb.ref(`students/${fbKey}`).remove();
        }
    };

    window.clearFinishedStudents = function () {
        if (!fbDb) return;
        getVisibleStudents().forEach(s => {
            if (s.status === "SELESAI" && s._fbKey) {
                delete alertedViolationsMap[s._fbKey];
                fbDb.ref(`students/${s._fbKey}`).remove();
            }
        });
    };

    window.publishBroadcast = function () {
        const msg = document.getElementById("inputBroadcastMessage")?.value.trim() || "";
        if (!fbDb) return;
        fbDb.ref("settings/announcement").set({
            message: msg,
            updatedAt: Date.now()
        });
        showCenterSwal({
            title: msg ? "Pengumuman Ditayangkan" : "Pengumuman Dihapus",
            text: msg ? "Pengumuman sekarang tampil di beranda aplikasi siswa." : "Banner pengumuman telah dihapus dari aplikasi siswa.",
            icon: "success",
            timer: 2000
        });
    };

    window.clearBroadcast = function () {
        const input = document.getElementById("inputBroadcastMessage");
        if (input) input.value = "";
        publishBroadcast();
    };

    window.exportReportToCsv = function () {
        const studentList = getVisibleStudents();
        if (studentList.length === 0) {
            showCenterSwal({ title: "Belum Ada Data", text: "Belum ada aktivitas siswa pada kelas akses Anda.", icon: "info", timer: 2200 });
            return;
        }
        const headers = ["Nama Siswa", "NIS", "Kelas", "Kode Ujian", "Status", "Pelanggaran", "Catatan Terakhir", "Sisa Waktu"];
        const rows = studentList.map(s => [
            s.studentName || s.name || "",
            s.nis || "",
            s.className || "",
            s.examCode || "",
            s.status || "",
            s.violationCount || 0,
            s.lastViolationReason || "",
            formatTime(s.remainingSeconds)
        ].map(val => `"${String(val).replace(/"/g, '""')}"`).join(","));

        const csv = "\uFEFF" + [headers.join(","), ...rows].join("\n");
        const blob = new Blob([csv], { type: "text/csv;charset=utf-8;" });
        const url = URL.createObjectURL(blob);
        const link = document.createElement("a");
        link.href = url;
        link.download = `Laporan_RobbaniExam_${Date.now()}.csv`;
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        URL.revokeObjectURL(url);
    };

    window.saveServerAndAdminConfig = function () {
        const url = document.getElementById("firebaseDbUrl")?.value.trim();
        const newPass = document.getElementById("newAdminPassword")?.value.trim();
        if (newPass && fbDb) {
            fbDb.ref("settings/adminAuth").set({ password: newPass, updatedAt: Date.now() });
        }
        if (url) {
            localStorage.setItem("robbani_firebase_db_url", url);
            location.reload();
        }
    };

    window.resetDefaultFirebase = function () {
        localStorage.setItem("robbani_firebase_db_url", DEFAULT_FIREBASE_URL);
        location.reload();
    };

    document.getElementById("filterClass")?.addEventListener("change", renderStudentsTable);
    document.getElementById("filterStatus")?.addEventListener("change", renderStudentsTable);
    document.getElementById("searchStudent")?.addEventListener("input", renderStudentsTable);

    applyRoleUI();
});
