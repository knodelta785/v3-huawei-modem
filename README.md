# ONGARAND - Modem Huawei Tool (Android)

Aplikasi sederhana untuk modem/router Huawei HiLink (alamat 192.168.8.1):
login, lihat sinyal (RSRP/SINR/band), kunci band LTE manual, pindai otomatis band terbaik (Game/Streaming/Zoom/Overall),
ganti mode jaringan, kelola APN, dan pilih WiFi 2.4 GHz / 5 GHz.

## Cara membuat APK
1. Pasang Android Studio (versi terbaru).
2. File > Open > pilih folder `HuaweiModemTool` ini. Tunggu Gradle sync selesai.
3. Colok HP (aktifkan USB debugging) lalu klik Run, atau Build > Build APK(s).
4. APK ada di `app/build/outputs/apk/debug/app-debug.apk`.

## Cara pakai
1. Sambungkan HP ke WiFi modem.
2. Buka aplikasi, isi alamat (default http://192.168.8.1), username `admin`, password modem.
3. Tab Sinyal, Band, APN. Tab Log menampilkan respons modem untuk diagnosis.

## Catatan
- Belum diuji pada modem asli. Struktur API mengikuti firmware HiLink Huawei umum,
  tapi tiap model/firmware bisa sedikit berbeda. Jika ada yang gagal, kirim isi tab Log (tanpa password).
- Mask band LTE: B1=1, B3=4, B8=80, B40=8000000000 (hex).
- Salah mengunci band bisa membuat sinyal hilang; tombol "Semua band" mengembalikan.

## Build tanpa Android Studio (GitHub Actions)
1. Buat repo baru di github.com, upload seluruh isi folder ini (termasuk folder `.github`).
2. Buka tab Actions > "Build APK" > Run workflow (atau otomatis saat push).
3. Setelah hijau, buka run tersebut, unduh artifact `ModemHuawei-apk` (isinya app-debug.apk).
4. Pasang di Android 11+ (izinkan "Install unknown apps").
