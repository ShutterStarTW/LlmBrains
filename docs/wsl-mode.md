# WSL Mode

On Windows, AgentHub can run every agent and companion tool either **natively** (the default)
or inside a **WSL (Windows Subsystem for Linux) distribution**. Use it if your toolchain
(node/npm, python/pip, etc.) is installed in WSL rather than on the Windows side.

## Enabling it

Go to **Settings/Preferences > Tools > AgentHub > Behavior** and look for *"Run agents in:"*
(only shown on Windows). Choose:

- **Windows (native)** — the default; agents run as regular Windows processes.
- **WSL** — a distribution picker appears next to it, listing the distributions detected via
  `wsl --list`, plus a *"Default distribution"* option that uses whichever distro WSL treats as
  default.

Windows (native) and each WSL distribution keep **their own settings**: which agents and companion tools are
enabled, what was detected as installed, and the update status. The headings of the agent and tool tables name the
environment they belong to (for example *AgentHub · WSL (Ubuntu)*), and the toolbar menu lists the selection of the
environment that is active. Switching the mode (or the distribution) brings that environment's own selection back and
starts a fresh auto-detect, since native Windows and a WSL distribution have a different set of "installed" tools. An
environment used for the first time starts with the default selection after its first detection.

Under each table, **Check installed agents** / **Check installed tools** ticks what the last detection found
installed and unticks what it found missing, which is the quickest way to set up a new environment.

## What changes in WSL mode

- **Every command runs inside the distro** — agent launch, detection, install, update and
  remove all execute through `wsl.exe --exec bash -lic '<command>'` in the chosen distribution,
  not on the Windows side.
- **Detection is distro-native, not interop-aware.** WSL automatically appends the Windows `PATH`
  inside the distro, which would otherwise make a Windows-only install of, say, Claude Code or
  npm look "installed" from inside WSL too. AgentHub filters out anything that only resolves to
  a `/mnt/c/...` Windows path, so detection reflects what is actually installed *in the distro* —
  not what's reachable through interop.
- **Linux/WSL-only agents become available.** ForgeCode, LeanCTL, Muse Code and Command
  Code have no native Windows build and are normally hidden on Windows; in WSL mode they run as
  regular Linux binaries inside the distro and show up like any other agent.
- **A missing toolchain produces a hint, not a silent failure.** A fresh distro often lacks
  `pip` or `npm`. Install and update commands first check for the tool: if only `pip3` exists,
  they use it instead of `pip`; if neither exists, they print a one-line hint
  (e.g. `sudo apt install python3-pip` or `sudo apt install nodejs npm`) instead of a bare
  "command not found".

## Projects, sessions and environment

The AgentHub tool window follows the mode. In WSL mode it reads the agents' data from the **distribution's home
directory** (for example `/home/me`), not from your Windows user profile: the sessions, skills, MCP servers,
instruction files and configuration files of the agents that run in the distro. Windows reaches those files over the
`\\wsl.localhost\<distro>` network share, so a first start of AgentHub in WSL mode may take a moment while the
distro wakes up.

- **Projects** that live in the distro are shown with their Linux path (`/home/me/project`). Their Git remote and
  branch are read from the repository's `.git` files. Projects under `/mnt/<drive>` are shown as Windows paths.
- **Resume** and **Launch** start the agent inside the distro, in the project's directory (`wsl.exe --cd`).
- **Open in IDE** opens the project through the share path.
- The IDE process's environment variables (`CLAUDE_CONFIG_DIR`, `XDG_*`, ...) are not applied: they belong to
  Windows, not to the distro.
- Sessions, projects and the other indexes are kept separately for Windows and for each distro, so switching the mode
  never mixes their data.

## Limitations

- Windows only. The setting does not appear on macOS or Linux.
- WSL must already be installed. If `wsl --list` returns nothing, the WSL option cannot be
  selected and a status label says so.
- Skill synchronization (Share, Promote, Resync, Undo) works in WSL mode on the distro's skill folders and links them with symlinks (never junctions); the backups stay in the Windows `~/.agenthub` folder.
- If a distribution disables the `/mnt` drive mount (a non-default `/etc/wsl.conf` setting), the
  scripted detect and update paths, which translate Windows paths to WSL paths, do not work.
