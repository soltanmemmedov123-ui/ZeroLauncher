---
name: ZeroLauncher UI customization goal
description: User wants to apply ZalithLauncher-TOWO-Reborn HTML UI design to ZeroLauncher-v14-fixed.zip — glassmorphic dark theme
type: project
---

User wants to apply the UI from `/sdcard/Download/ZalithLauncher-TOWO-Reborn.html` to `ZeroLauncher-v14-fixed.zip` — pixel-perfect match of style, theme, and icons.

**Why:** User wants ZeroLauncher to have the same glassmorphic dark UI (deep purple/blue background, blur effects, cyan/green/purple accents, Inter font) as the TOWO Reborn design.

**How to apply:** The zip contains **source code** (ZalithLauncher TOWO Reborn 1.4.4.5), not a compiled APK. Successfully extracted to `/sdcard/Download/ZeroLauncher-source/ZalithLauncherTOWOReborn-1.4.4.5-TOWO/` using proot-distro Ubuntu (user installed unzip inside proot). It's a Gradle Android project based on PojavLauncher (`net.kdt.pojavlaunch` package). Key source paths known: `ZalithLauncher/src/main/java/net/kdt/pojavlaunch/` contains Java files (MainActivity, customcontrols, authenticator, services, tasks, etc.).

**Resource directory structure (discovered):**
`ZalithLauncher/src/main/res/` contains: `anim/`, `drawable/`, `font/`, `layout/`, `menu/`, `mipmap-anydpi-v26/`, `mipmap-hdpi/`, `mipmap-mdpi/`, `mipmap-xhdpi/`, `mipmap-xxhdpi/`, `mipmap-xxxhdpi/`, `values/`, `values-ar/`, `values-de/`, `values-fr/`, `values-night/`, `values-pt-rBR/`, `values-ru/`, `values-vi/`, `values-zh-rCN/`, `values-zh-rTW/`, `xml/`.

Still need to see actual files inside `layout/`, `values/`, and `drawable/` — user needs to run `ls` on those directories. The HTML file is a complete design reference with: color system (CSS variables), glass card styles, icon SVGs, layout structure (left nav + right content panels), and component patterns.

**Workflow note:** User must run commands in proot Ubuntu (`! proot-distro login ubuntu`) and paste output back — Bash tool is broken on this Termux. Long outputs get truncated in the chat; use targeted `ls` or `find ... | head` commands.
