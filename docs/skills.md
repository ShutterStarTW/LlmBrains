# Skills Browser

Open **View → Tool Windows → AgentHub → Skills** to inspect skills on the IDE host.

## Browsing

Choose **Global** to browse the host user's skill directories, or **Project** plus a project to
inspect that project's skill directories. The open IDE project is always available, even if no agent
session has been discovered for it. WSL launch mode does not switch this browser to a WSL home.

- **Search** matches skill titles, names, descriptions and source paths. Each tab keeps its own
  search text.
- **Filters ▾** (next to the scope selector) opens a popup with the state filter (**All**,
  **Shared**, **Conflicts**), the ownership filter and an agent filter. Choices apply immediately,
  and the button shows how many are active, for example **Filters (2) ▾**.
- The **summary** counts shared, in-sync and conflicting skill groups separately from the number of
  occurrences. It also shows the totals of healthy, broken and unknown targets and the managed-agent
  coverage.
- **Discovery warnings** appear in a collapsed **N discovery warnings** bar above the list. Click it
  to expand.
- **Refresh** rescans the selected scope.

The skill title is the first H1 heading in `SKILL.md`, or the skill name if there is none. Each
source directory is a separate row. If several agents discover the same compatibility directory, it
appears once with all their icons; hover an icon to see the agent name.

## Skill details

Select a source to see its name, scope and state in the header, and these tabs:

- **Overview** — description, path, sources and script warnings. **Open SKILL.md** opens the file in
  the editor; **Reveal in Files** shows its directory. The file list shows at most 200 files within
  four directory levels; double-click a file, or focus it and press **Enter**, to open it. If the
  count differs from what is listed, the page explains the scan limits (counts go up to twelve
  levels). Each additional source has its own **Open SKILL.md** and **Reveal in Files**, plus
  **Resolve Conflict…** when its content differs from the shared source.
- **Agents** (shared skills only) — one block per agent with its state, ownership and path, plus
  **Resync / Repair** and **Stop Sharing** where they apply.
- **History** (only when there is history or a backup) — the most recent operations (up to 50),
  each with an **Undo** button while it can still be undone, and **Restore Backup…**.

In a narrow tool window, selecting a row opens its details under a bar that names the skill. The
search box and filters are hidden while details are open; the back button returns to the list. In a
wide window the list/details divider sits at the same position on the Projects, Agents and Skills
tabs. Long names and paths stay on one line; hover to see the full text.

## Sharing and promoting

A shared skill offers **Share…**, a checklist that opens with the agents that already have the
skill from the shared source. AgentHub reads this from disk, so links and copies made earlier or by
hand count too.

- Check an agent to share with it; uncheck one to stop sharing. Both changes are one reviewed
  operation with one history entry and one Undo.
- The button stays disabled until something changes.
- Agents whose sharing AgentHub did not create, and cannot remove, stay checked and greyed out
  (see [Settings](#settings)).

A skill that exists only for one agent offers the same **Share…** button: it moves the skill into the shared folder (agents that read that folder directly are checked and greyed out) and can also share it with more
agents in the same step. Every agent with a skill folder of its own has a validated sync adapter; Freebuff has none and reads the shared folder directly, so it is always shown checked and greyed out.

Every change is planned as a dry run on a background thread, and you must review the preview before
you click Apply. The preview lists each target's current state, planned change, path, requested
mode, effective link/copy representation and warnings. Existing content is backed up before it is
replaced. Apply is disabled when the plan has nothing to execute.

## Conflicts and repair

Managed targets offer **Resync / Repair** and **Stop Sharing** on the **Agents** tab. For a
copy that differs, **Resolve Conflict…** opens a directory summary and a JetBrains text diff. The
open skill is always on the left. You choose **Keep shared**, **Keep agent's** or **Keep both**, and
the resulting plan is previewed. Between two agent copies you can also make one the shared skill,
or replace the other copy with it (always after a backup). A copy that ships with the agent itself
is never overwritten. The same actions are in the details pane and the row's context menu.

**Identical contents** and **Different contents** compare skill-directory fingerprints. They do not
mean AgentHub owns the directories or that synchronization is configured. Provider warnings stay
visible next to the skills that were discovered.

## Migrating duplicates

When identical agent-specific copies are safe to merge, **Migrate duplicates…** opens a review list.
Each selected skill runs on its own: it is moved to the shared folder, then shared with the other
agents. The status line reports progress. Cancelling takes effect after the current skill, and a
partial result names the candidates you can retry.

**Clean up redundant copies…** removes links or copies that an agent no longer needs because it
reads the shared folder directly. Only copies identical to the shared skill are removed, always after
a backup.

## History, Undo and backups

- **Undo** first shows the recorded paths, affected agents and available backups, then re-checks the
  current state. If the files were edited after the operation, Undo refuses instead of overwriting
  them. The journal survives an IDE restart.
- **Restore Backup…** is separate from Undo. It backs up the current target before restoring the
  version you select.
- AgentHub keeps, per skill instance, the five newest backups or all backups newer than 30 days.

## Settings

The settings button opens a dialog with these options:

- **Link or Copy** as the preferred sharing mode. A link falls back to Copy only if the reviewed plan
  already showed Copy as the effective representation.
- Whether replacement backups are created.
- **Review the plan before applying** (on by default). When off, changes run right away without a
  preview; backups are still made. Choices you must make (which agents, which version, which
  backup) are always shown.
- **Manage existing skills too, not only the ones AgentHub shared** (off by default). Off: AgentHub
  removes only the links and copies it created. On: unchecking an agent in **Share…** (or
  **Stop Sharing**) also removes an existing link to the shared skill, or a copy identical to it.
  This always happens after a backup, and never for a copy whose content differs from the shared
  skill.

## Safety rules

- Synchronization always starts from you.
- A partial failure does not roll back independent targets that succeeded. Failed targets can be
  rediscovered and retried, and a result that needs recovery keeps its backup details visible.
- For project-scoped changes, review the IDE's Git changes before committing. AgentHub never runs
  `git add`, commits, edits `.gitignore` or executes skill scripts.

## Windows and WSL

On Windows, directory sharing on the host uses junctions where possible, with Copy as a reviewed
fallback. The Skills browser works on the IDE host filesystem only. While WSL launch mode is on,
mutating actions (synchronization, Restore, Undo) are disabled: otherwise AgentHub could write to the
Windows user's skill directories while the agents read a WSL home. Switch to **Windows (native)**
before applying host-side skill changes.


## Shared data across IDEs

Skill ownership, settings, history and backups are stored in **~/.agenthub**, shared by all JetBrains
IDEs under the same OS user. Data from the old per-IDE files is imported once and the originals stay
in place. **Settings** shows the data location. A change made in another IDE appears when you return
to the window or refresh.

To use another folder, set **AGENTHUB_HOME** or the JVM option `-Dagenthub.home=...` (the JVM option
wins). Pick a local folder if your home is on a network or synced drive. Backups contain the skill
files needed to restore them. Toolbar preferences and detection results stay per IDE.

If the folder cannot be used, AgentHub keeps its data in memory for the session and warns you. If
another process holds the lock, or the data was written by a newer version, changes are blocked and
an error is shown.
