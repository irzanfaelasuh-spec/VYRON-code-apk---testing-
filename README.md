# VYRON IDE untuk Android

**Versi:** 0.2.0  
**Package:** `com.vyron.ide`  
**Target:** aplikasi Android APK  
**Minimum:** Android 7.0 / API 24

Ini adalah kelanjutan Android dari `VYRON-IDE-0.1.0.zip` yang kamu unggah. File HTML editor lama dan fitur yang sudah ada tetap disimpan; sumber aslinya juga dipertahankan tanpa perubahan di `legacy/`. APK memuat antarmuka editor sebagai aset lokal di dalam aplikasi, jadi VYRON tidak memerlukan website atau server untuk dibuka.

## Yang sudah ditambahkan

- Explorer hierarkis, tab multi-file, syntax coloring, Outline, pencarian, command palette, autosave, pengaturan tema/font/tab size dan shortcut keyboard dari editor lama.
- Badge ikon file sesuai ekstensi HTML, CSS, JS/TS, Python, C/C++, Java, Go, Rust, JSON, Markdown, Shell, SQL, XML dan Kotlin.
- Ukuran area sentuh diperbesar untuk HP; layout menyesuaikan ponsel dan layar yang lebih besar.
- Preview HTML dengan tombol fullscreen dan tombol untuk kembali ke editor.
- Import banyak file sekaligus atau satu folder proyek lengkap melalui Android Storage Access Framework. Struktur subfolder dipertahankan; tidak perlu ZIP.
- Export workspace kembali ke folder biasa yang kamu pilih di penyimpanan Android.
- Workspace privat aplikasi yang sama dipakai editor dan terminal. Simpan file di editor, lalu file tersebut tersedia dari shell. Tekan **Refresh from terminal** setelah membuat atau menghapus file lewat terminal.
- Terminal native interaktif berbasis PTY menggunakan library `terminal-view` dan `terminal-emulator` dari proyek Termux. Shell yang dijalankan adalah `/system/bin/sh` Android, tanpa root. Panel menyediakan tombol CTRL, ESC, TAB, panah dan Ctrl+C.
- Error JavaScript yang tidak tertangani saat Run dipetakan ke nomor baris dan diberi titik merah di gutter editor.
- Loading screen animasi 4 detik dari proyek awal tetap dipertahankan.
- Ikon launcher VYRON minimalis dalam format adaptive icon dan beberapa ukuran launcher lama.
- GitHub Actions disiapkan untuk membuat APK debug yang bisa dipasang serta APK release unsigned setiap ada push ke `main`/`master`, atau saat workflow dijalankan manual.

## Batas runtime terminal — penting

Build ini menyertakan **terminal emulator dan lapisan PTY Termux**, bukan seluruh Linux userland/bootstrap Termux. Shell yang dijalankan adalah `/system/bin/sh` dan perintah bawaan Android tersedia. Perintah seperti `pkg`, `apt`, `python3`, `node`, `clang`, `rustc`, `go`, `ruby` dan `php` **belum dibundel**. Tombol Run mencoba menjalankan perintah runtime yang sesuai; apabila runtime tidak ada di perangkat, shell akan memberi pesan bahwa perintah tidak ditemukan.

Jadi terminal interaktifnya sudah native, bukan simulasi terminal HTML, tetapi paket Linux seperti Termux belum seluruhnya disertakan. Milestone berikutnya adalah membuat runtime pack Android yang ditandatangani, bisa diperbarui, dan kompatibel dengan ABI perangkat.

## Seberapa dekat dengan VS Code?

Ini belum merupakan port penuh VS Code atau Code-OSS. UI editor yang lama sengaja dipertahankan agar kode dan fitur sebelumnya tidak dibuang. Editor saat ini masih menggunakan textarea dengan syntax-highlighting overlay, bukan Monaco. Fitur bahasa-server IntelliSense lengkap, extension host, debugger, Problems pipeline penuh, dan multi-cursor dengan beberapa caret sungguhan belum tersedia. `Ctrl+D` saat ini memilih kecocokan berikutnya, bukan membuat beberapa caret aktif sekaligus.

Catatan teknis dan hasil riset Termux/VS Code ada di [docs/ARCHITECTURE-AND-RESEARCH.md](docs/ARCHITECTURE-AND-RESEARCH.md).

## Status paket ini

Source project dan pipeline CI sudah disiapkan, tetapi file APK belum dilampirkan: lingkungan pembuatan ini tidak memiliki Android SDK maupun Gradle untuk menjalankan build lokal dan uji emulator. APK debug akan dihasilkan oleh workflow GitHub setelah isi folder proyek di-push ke repository. Proyek ini juga belum di-push ke remote karena belum ada repository tujuan yang dapat dipakai.

## Membuat APK lewat GitHub

1. Buat repository GitHub kosong bernama `VYRON-IDE` di akunmu.
2. Upload atau push **isi folder proyek ini** ke root repository. Pastikan folder `.github/workflows/` ikut ter-upload.
3. Buka tab **Actions → Build VYRON APK**. Push ke branch `main` atau `master` juga akan memulai build otomatis.
4. Setelah workflow selesai, buka hasil run dan download artifact **VYRON-IDE-debug-apk**. Di dalamnya ada `app-debug.apk` yang ditandatangani debug key dan bisa dipasang untuk pengujian.

Artifact release masih unsigned. Sebelum dibagikan ke pengguna lain, tambahkan keystore release milikmu melalui GitHub Secrets dan konfigurasi signing Gradle.

## Build melalui Android Studio

Buka folder proyek ini sebagai Android project. Gunakan JDK 17, Android SDK Platform 36, Build Tools 36.0.0 dan Gradle 8.13. Lakukan Gradle Sync, lalu pilih **Build → Build APK(s)**. Sinkronisasi pertama membutuhkan koneksi internet untuk mengambil dependency Termux dari JitPack.

Untuk command line, instal Gradle 8.13 lalu jalankan:

```bash
gradle --no-daemon :app:assembleDebug
```

APK debug akan berada di `app/build/outputs/apk/debug/app-debug.apk`.

## Checklist pengujian di perangkat

1. Pasang APK dan buka VYRON IDE. Tunggu intro animasi selesai.
2. Buat `index.html`, `style.css`, dan `app.js`; buka Preview dan uji tombol fullscreen.
3. Pilih **Import a folder**, lalu pilih folder proyek berisi subfolder dan beberapa file source. Pastikan badge ekstensi tampil.
4. Buka Terminal, lalu uji `pwd`, `ls`, `mkdir test`, dan `echo hello > test/hello.txt`. Tekan **Refresh from terminal** di Explorer.
5. Edit dan simpan satu file di editor; pastikan isi file yang sama dapat dibaca dari terminal.
6. Pilih **Export workspace**, lalu periksa apakah seluruh hierarki file tersalin ke folder tujuan.
7. Masukkan syntax error ke JavaScript, jalankan, lalu pastikan nomor baris dan titik merah muncul.

## Struktur proyek

```text
VYRON-IDE-Android/
├── app/
│   └── src/main/
│       ├── java/com/vyron/ide/MainActivity.kt
│       ├── assets/www/index.html
│       ├── res/                 # tema dan ikon launcher
│       └── AndroidManifest.xml
├── branding/                    # master ikon VYRON
├── docs/                        # arsitektur dan catatan riset
├── legacy/                      # sumber asli dari ZIP
├── licenses/                    # pemberitahuan lisensi pihak ketiga
└── .github/workflows/            # pipeline build APK
```

## Catatan penggunaan

- File proyek disimpan di direktori privat aplikasi `files/vyron-workspaces/<workspace-key>/`. Aplikasi tidak mengambil alih seluruh storage; import dan export dilakukan lewat pemilih folder/file Android.
- Import versi ini fokus pada file source teks UTF-8, maksimum 5 MiB per file dan total import sekitar 60 MiB. File binary dan file terlalu besar dilewati. Workspace editor membaca sampai kira-kira 12 MiB teks.
- Jangan impor seluruh `node_modules`, folder cache, atau dependency build yang sangat besar pada versi awal ini.
- Aplikasi tidak meminta akses root dan tidak memerlukan Termux terpasang sebagai aplikasi terpisah.
- Library `terminal-view` dan `terminal-emulator` Termux digunakan sebagai dependency Apache-2.0. Kode aplikasi Termux berlisensi GPL-3.0 tidak disalin ke proyek ini.
