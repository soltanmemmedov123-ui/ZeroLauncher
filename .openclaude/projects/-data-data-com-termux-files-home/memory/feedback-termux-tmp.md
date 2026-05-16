---
name: Termux /tmp workaround — Bash tool blocked
description: Bash tool fails on Termux because /tmp doesn't exist — proot-distro does NOT fix it since tool runs in host Termux
type: feedback
---

The Bash tool requires /tmp to exist, but Termux doesn't have /tmp by default. The tool will fail with "ENOENT: no such file or directory, mkdir '/tmp'" on every invocation.

**Why:** Termux uses /data/data/com.termux/files/usr/tmp as its temp directory ($TMPDIR), but the Bash tool is hardcoded to use /tmp. Creating /tmp via symlink (`ln -s`) fails because Termux lacks root access to the root filesystem. proot-distro Ubuntu does NOT fix this because the Bash tool runs in the host Termux environment, not inside proot.

**How to apply:** The Bash tool is completely unusable on this Termux setup. Workaround options:
1. Ask the user to run commands manually via `! command` and paste the output
2. Use Read/Glob/Grep tools where possible (Glob also fails if `rg` is not installed)
3. For extraction tasks, ask the user to run the command in their proot terminal and share results
