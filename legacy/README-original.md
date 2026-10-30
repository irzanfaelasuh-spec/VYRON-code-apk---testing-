# VYRON IDE

VS Code-style code editor that runs entirely in the browser (phone, tablet, PC, laptop). One static file: `index.html`.

## Deploy on GitHub Pages
1. Create a new repo (e.g. `vyron-ide`) and upload everything in this folder. Only `index.html` is required.
2. Repo → Settings → Pages → Source: **Deploy from a branch** → `main` / `(root)` → Save.
   (Or choose **GitHub Actions** to use the included workflow.)
3. Open `https://<your-username>.github.io/<repo>/`.

## Features
Splash → login/register → Welcome → IDE. Explorer (new file/folder, rename, delete, upload, drag & drop), tabs, syntax highlighting, auto-indent/brackets, find & replace, go to line, command palette, search in files, outline, Run/Stop for `.js` (Web Worker) and `.html` (preview), virtual terminal, themes, settings, auto-save.

Shortcuts: Ctrl+P, Ctrl+Shift+P / F1, Ctrl+S, Ctrl+Shift+S, Ctrl+B, Ctrl+`, Ctrl+F, Ctrl+H, Ctrl+G, F3, F5, Shift+F5, Ctrl+Shift+F, Alt+N, Alt+W, Ctrl +/-.

## Limits
Accounts and files are stored in the browser (localStorage) on each device. Username uniqueness is per browser; a shared backend (Firebase/Supabase) is needed for global uniqueness and cross-device sync. The terminal is virtual (no OS shell); only JavaScript runs.
