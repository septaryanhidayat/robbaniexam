@echo off
echo Menjalankan Robbani Exam Admin Dashboard di http://localhost:8085 ...
start http://localhost:8085
python -m http.server 8085 --directory "%~dp0admin-dashboard"

