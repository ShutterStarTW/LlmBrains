# WSL Mode

On Windows, AgentHub can run every agent and companion tool either **natively** (the default)
or inside a **WSL (Windows Subsystem for Linux) distribution**. This is useful if your toolchain
(node/npm, python/pip, etc.) already lives in WSL rather than on the Windows side.

## Enabling it

Go to **Settings/Preferences > Tools > AgentHub > Behavior** and look for *"Run agents in:"*
(only shown on Windows). Choose:

- **Windows (native)** — the default; agents run as regular Windows processes.
- **WSL** — a distribution picker appears next to it, listing the distributions detected via
  `wsl --list`, plus a *"Default distribution"* option that uses whichever distro WSL treats as
  default.

Switching the mode (or the selected distribution) clears the current detection results and
triggers a fresh auto-detect, since native Windows and a WSL distribution have a different set
of "installed" tools.

## What changes in WSL mode

- **Every command runs inside the distro** — agent launch, detection, install, update and
  remove all execute through `wsl.exe --exec bash -lic '<command>'` in the chosen distribution,
  not on the Windows side.
- **Detection is distro-native, not interop-aware.** WSL automatically appends the Windows `PATH`
  inside the distro, which would otherwise make a Windows-only install of, say, Claude Code or
  npm look "installed" from inside WSL too. AgentHub filters out anything that only resolves to
  a `/mnt/c/...` Windows path, so detection reflects what is actually installed *in the distro* —
  not what's reachable through interop.
- **Linux/WSL-only agents become available.** ForgeCode, LeanCTL, Muse Code, Plandex and Command
  Code have no native Windows build and are normally hidden on Windows; in WSL mode they run as
  regular Linux binaries inside the distro and show up like any other agent.
- **Missing toolchain gets a friendly hint, not a silent failure.** A fresh distro often lacks
  `pip` or `npm`. Install/update commands first check for the tool: if only `pip3` is available,
  they use that instead of `pip`; if neither exists, you get a one-line hint
  (e.g. `sudo apt install python3-pip`, or `sudo apt install nodejs npm`) instead of a cryptic
  "command not found".

## Limitations

- Windows-only (the setting doesn't appear on macOS/Linux, where there's nothing to toggle).
- Requires WSL to already be installed on the machine; if `wsl --list` returns nothing, the WSL
  option can't be selected and a status label says so.
- If a distribution disables the `/mnt` drive mount (a non-default `/etc/wsl.conf` setting), the
  scripted detect/update paths that rely on Windows↔WSL path translation won't work.
