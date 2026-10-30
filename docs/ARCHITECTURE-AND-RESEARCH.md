# VYRON IDE — architecture and research notes

Research snapshot: 30 October 2026.

## 1. What the uploaded project actually contained

The uploaded ZIP contained a single-page HTML/JavaScript editor prototype and a small Pages workflow. Its file tree, tabs, text editor, command palette, settings, preview, and terminal-like panel were implemented in the browser. The terminal in that ZIP was a simulated command interpreter over a JavaScript object/localStorage; it did not open an Android/Linux process. The original HTML is preserved at `legacy/index-original.html`.

The Android port therefore keeps that existing UI rather than deleting it, but connects it to native storage and a native terminal. The local editor page is packaged under `app/src/main/assets/www/` and served through `WebViewAssetLoader`.

## 2. Termux research: separate terminal UI from Linux userland

Termux's repository describes the main app as the UI and terminal-emulation layer, while installable command-line packages are maintained separately in `termux/termux-packages`. The terminal UI can be embedded independently: the `terminal-view` dependency automatically brings `terminal-emulator`. The upstream JitPack instructions identify the exact published coordinate `com.termux.termux-app:terminal-view:0.118.0` and recommend the `com.termux` group naming. The upstream JitPack guide documents the published `terminal-view` artifact coordinate at `0.118.0`. A `v0.119.0-beta.3` prerelease tag exists in the releases history, but VYRON pins to the documented 0.118.0 artifact for this first build. The v0.118.x source interface was checked directly before implementing the Kotlin callbacks.

The distinction matters:

- `terminal-view` + `terminal-emulator` provide terminal rendering, input handling, escape-sequence parsing and the PTY-oriented session layer.
- A shell executable and a userland provide the actual commands.
- Termux's `pkg`/`apt`, Bash/Zsh, Python, Node.js, Clang, Git and other packages belong to its package environment; merely importing the terminal view library does not install them.

VYRON currently creates an interactive `TerminalSession` that launches `/system/bin/sh` in the app-private workspace. It does not request root. Android's system shell and built-in tools are available, but full Termux package management and common development runtimes are not bundled. This is an intentional first milestone, not a claim that the entire Termux environment has already been embedded.

### Recommended next runtime milestone

Choose one of these explicitly before shipping a bigger runtime:

1. **Bundled runtime pack:** package a tested set of Android-compatible binaries (for example Python and Git) for each supported ABI, with checksums, licences, storage limits and update controls.
2. **Termux bootstrap integration:** port and maintain the required Termux bootstrap/package-manager pieces inside VYRON's application sandbox. This is substantially more involved and requires careful licence review and ongoing repository maintenance.
3. **Optional external Termux integration:** delegate supported commands to a separately installed Termux app. This is smaller, but it does not meet the strict “bundled terminal” requirement and creates a separate app dependency.

Do not add `pkg` as a fake shell command. A real package manager needs its repository metadata, signing keys, bootstrap files, ABI packages, update and recovery logic.

## 3. VS Code research: what “VS Code-like” really entails

VS Code is not only a dark theme and a file explorer. Its editing model includes a mature editor engine, language services, diagnostics, extension integration, task orchestration and debugger integration. The public VS Code editing documentation describes genuine multi-cursor selections: several independent cursors can edit simultaneously, with commands such as Ctrl+D and Ctrl+Shift+L. It also has language-driven IntelliSense, navigation and Problems diagnostics. A terminal panel is only one part of the workbench.

VYRON's first port retains the earlier textarea/highlight-overlay editor. This is useful as a functional foundation and avoids deleting the user's current code, but it is **not Monaco or Code-OSS**. The following should be separate, testable milestones:

- Replace the textarea with a bundled editor engine (Monaco is closest to the VS Code desktop experience; CodeMirror 6 is a lighter alternative for mobile memory and APK size).
- Add a language-server protocol client and per-language language servers. Merely highlighting `def`, `class` or braces is not IntelliSense.
- Implement real multi-selection/multi-cursor behaviour in the editor engine; a single HTML textarea cannot represent multiple live selections.
- Add a structured Problems model that receives compiler/linter diagnostics with file URI, line, column, severity and message.
- Build a debugger/task system as explicit native/runtime integrations, not as mocked buttons.
- Add a controlled extension model. VS Code desktop extensions are not automatically Android-compatible.

The current version marks source lines for uncaught JavaScript Worker errors when the browser reports a line number. The native shell currently shows its own stderr/stdout in the terminal; it does not yet convert arbitrary compiler output into clickable editor diagnostics.

## 4. Android file and WebView architecture

### File access

Android's Storage Access Framework is the correct user-mediated model for this app:

- `ACTION_OPEN_DOCUMENT` imports selected files.
- `ACTION_OPEN_DOCUMENT_TREE` grants access to a selected folder and its descendants.
- VYRON copies supported UTF-8 source files into its app-private workspace, preserving relative paths. The user can export the workspace into a selected folder later.

This avoids broad storage permissions and means normal project files are not hidden in a ZIP. The import limit is 5 MiB per text file and the web editor currently loads at most approximately 12 MiB of text at a time. Binary assets and oversized files are skipped in this first version.

### Web/native bridge

The IDE page uses `WebViewAssetLoader` and a secure app-assets origin. The Android side uses `WebViewCompat.addWebMessageListener`, with the trusted origin allowlisted and `isMainFrame` checked. The HTML preview iframe is sandboxed without `allow-same-origin`, so preview content does not receive the native message bridge. Validate every native path before touching the filesystem; `safeWorkspaceFile()` rejects traversal and canonical paths outside the workspace.

### Terminal and workspace

The PTY starts with:

- executable: `/system/bin/sh`
- working directory: the selected app-private workspace
- `HOME`: a separate private VYRON shell-home directory
- `TERM=xterm-256color`
- `PATH=/system/bin:/system/xbin:/vendor/bin`

The editor writes its saved text files into that same workspace. Files created externally from the terminal appear in the Explorer after **Refresh from terminal**. The shell is still Android's app sandbox: it is not a Linux container and does not have Termux's package repository.

## 5. Build and CI choices

- Android Gradle Plugin: 8.13.2
- Gradle: 8.13
- Kotlin Gradle Plugin: 2.4.21
- JDK: 17
- Compile SDK: 36
- Target SDK: 35
- Minimum SDK: 24
- Termux terminal-view: 0.118.0 (resolved from JitPack)

The Kotlin/Gradle/AGP compatibility table lists Kotlin Gradle Plugin 2.4.20–2.4.21 as compatible with AGP 8.5.2–9.3.1 and Gradle 7.6.3–9.7.0, so this build's selected versions are within the documented compatibility range. AGP 8.13 requires Gradle 8.13.

The GitHub Actions workflow runs `assembleDebug` and `assembleRelease`. The debug APK is signed by the CI debug key and is intended for device testing. The release APK is unsigned until a release keystore is configured; do not distribute the unsigned file as a production install.

## 6. Verification status and known gaps

Verified in this workspace:

- Original ZIP extracted successfully and original HTML retained.
- Modified HTML JavaScript passes `node --check`.
- Android project, manifest, Gradle configuration, icon assets and CI workflow are present.
- No Gradle executable or Android SDK is installed in the authoring environment, so a local APK build, emulator launch and physical-device PTY test could not be performed here.
- No GitHub repositories were returned for the connected GitHub account, and the available GitHub actions do not expose repository creation. The workflow is included, but the project has not been pushed to a remote repository in this run.

Before a public APK release, run the test checklist in the root README on at least one physical ARM64 Android device and one emulator. Verify the JitPack dependency resolution, PTY startup, keyboard input, folder import/export, Android lifecycle behaviour and terminal process cleanup.

## 7. Primary references

- Termux libraries and JitPack coordinates: <https://github.com/termux/termux-app/wiki/Termux-Libraries>
- Termux app repository and separation of app from packages: <https://github.com/termux/termux-app>
- Termux package repository: <https://github.com/termux/termux-packages>
- Termux app/library version and installation support: <https://github.com/termux/termux-app/releases>
- Termux license and Apache-2.0 terminal library exception: <https://github.com/termux/termux-app/blob/master/LICENSE.md>
- VS Code basic editing and true multi-cursor behaviour: <https://code.visualstudio.com/docs/editing/codebasics>
- VS Code code navigation: <https://code.visualstudio.com/docs/editing/editingevolved>
- Android Storage Access Framework: <https://developer.android.com/training/data-storage/shared/documents-files>
- Android secure JavaScript messaging for WebView: <https://developer.android.com/develop/ui/views/layout/webapps/native-api-access-jsbridge>
- Kotlin/Gradle/AGP compatibility: <https://kotlinlang.org/docs/gradle-configure-project.html>
- Android Gradle Plugin / Gradle compatibility: <https://developer.android.com/build/releases/about-agp>
