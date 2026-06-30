#!/usr/bin/env bash
# MIRROR: llmbrains.ps1 — any logic change here must be mirrored in the PowerShell script
set -uo pipefail

# ANSI color codes
COL_RESET="\033[0m"
COL_YELLOW="\033[0;33m"
COL_BRIGHT_GREEN="\033[1;32m"
COL_BRIGHT_WHITE="\033[1;37m"
COL_DIM="\033[2m"

usage() {
  cat <<'USAGE'
Usage:
  llmbrains.sh check <friendly-name> <version-command> [install-hint]
  llmbrains.sh update <friendly-name> <binary> <update-command> [install-hint]
  llmbrains.sh check-all <agent-definitions-json>
  llmbrains.sh version-all <agent-definitions-file>
  llmbrains.sh update-all <agent-definitions-file> <active-agent-ids>
  llmbrains.sh detect-all <agent-definitions-file> <output-file>
USAGE
}

# version-all/update-all/detect-all receive their agent-definitions payload via a temp file
# (rather than inline on the command line) for parity with the PowerShell script, where the
# payload's raw `"`, `$`, and `|` characters get mangled when passed as native-exe arguments.
read_agent_definitions() {
  local path="$1"
  cat "$path"
  rm -f "$path"
}

if [[ $# -lt 1 ]]; then
  usage
  exit 1
fi

subcommand="$1"
shift

case "$subcommand" in
  check)
    if [[ $# -lt 2 ]]; then
      echo "llmbrains.sh: check requires a name and version command" >&2
      usage >&2
      exit 1
    fi
    name="$1"
    version_command="$2"
    install_hint="${3:-}"

    binary="${version_command%% *}"
    if [[ -z "$binary" ]]; then
      echo "llmbrains.sh: could not determine binary for $name" >&2
      exit 1
    fi

    if command -v "$binary" >/dev/null 2>&1; then
      read -ra _cmd <<< "$version_command"
      version_output=$("${_cmd[@]}" 2>&1)
      status=$?
      version_first=$(echo "$version_output" | head -1)
      if [[ $status -eq 0 ]]; then
        printf "  ${COL_BRIGHT_GREEN}✓${COL_RESET}  %-20s ${COL_BRIGHT_GREEN}%s${COL_RESET}\n" "$name" "$version_first"
        exit 0
      else
        printf "  ${COL_YELLOW}⚠${COL_RESET}  %-20s ${COL_YELLOW}installed, version check failed (exit %d)${COL_RESET}\n" "$name" "$status"
        exit 1
      fi
    else
      if [[ -n "$install_hint" ]]; then
        printf "  ${COL_YELLOW}✗${COL_RESET}  %-20s ${COL_YELLOW}not installed${COL_RESET}  →  %s\n" "$name" "$install_hint"
      else
        printf "  ${COL_YELLOW}✗${COL_RESET}  %-20s ${COL_YELLOW}not installed${COL_RESET}\n" "$name"
      fi
      exit 1
    fi
    ;;
  update)
    if [[ $# -lt 3 ]]; then
      echo "llmbrains.sh: update requires a name, binary, and update command" >&2
      usage >&2
      exit 1
    fi
    name="$1"
    binary="$2"
    update_command="$3"
    install_hint="${4:-}"

    if command -v "$binary" >/dev/null 2>&1; then
      printf "  ${COL_BRIGHT_WHITE}↻${COL_RESET}  ${COL_BRIGHT_WHITE}%s${COL_RESET}\n" "$name"
      read -ra _cmd <<< "$update_command"
      update_output=$("${_cmd[@]}" 2>&1)
      status=$?
      display_output=$(echo "$update_output" | grep -vE "^[[:space:]]*(Collecting|Downloading|Installing collected|Using cached|Building wheels|Obtaining|Attempting|Running command|Getting requirements|Preparing metadata|Requirement already satisfied|Uninstalling|Successfully uninstalled|Found existing installation)" | grep -vE "^(WARNING|NOTE|DEPRECATION|ERROR: pip|\[notice\])" | grep -v "which is incompatible")
      if [[ -n "$display_output" ]]; then
        echo "$display_output" | sed 's/^/     /'
      fi
      if [[ $status -eq 0 ]] || echo "$update_output" | grep -q "Successfully installed"; then
        if echo "$update_output" | grep -qE "up to date|Requirement already satisfied|already up-to-date|already installed"; then
          printf "  ${COL_BRIGHT_GREEN}~${COL_RESET}  %-20s ${COL_DIM}up to date${COL_RESET}\n\n" "$name"
          exit 2
        else
          printf "  ${COL_BRIGHT_GREEN}✓${COL_RESET}  %-20s ${COL_BRIGHT_GREEN}updated${COL_RESET}\n\n" "$name"
          exit 0
        fi
      else
        printf "  ${COL_YELLOW}✗${COL_RESET}  %-20s ${COL_YELLOW}update failed (exit %d)${COL_RESET}\n\n" "$name" "$status"
        exit 1
      fi
    else
      hint="${install_hint:-install instructions unavailable}"
      printf "  ${COL_YELLOW}✗${COL_RESET}  %-20s ${COL_YELLOW}not installed${COL_RESET}  →  %s\n\n" "$name" "$hint"
      exit 1
    fi
    ;;
  check-all)
    if [[ $# -lt 1 ]]; then
      echo "llmbrains.sh: check-all requires agent definitions JSON" >&2
      usage >&2
      exit 1
    fi
    agents_json="$1"

    clear
    printf "${COL_BRIGHT_WHITE}📋 Checking agents & companion tools${COL_RESET}\n"
    printf "${COL_DIM}%s${COL_RESET}\n" "──────────────────────────────────────────────────"

    ok_count=0
    warn_count=0

    while IFS='|' read -r name command version_args install_hint; do
      if [[ -n "$name" ]]; then
        version_command="$command $version_args"
        "$0" check "$name" "$version_command" "$install_hint"
        if [[ $? -eq 0 ]]; then ((ok_count++)); else ((warn_count++)); fi
      fi
    done < <(echo "$agents_json" | tr '~' '\n')

    printf "${COL_DIM}%s${COL_RESET}\n" "──────────────────────────────────────────────────"
    if [[ $warn_count -gt 0 ]]; then
      printf "  ${COL_BRIGHT_GREEN}✓ %d OK${COL_RESET}   ${COL_YELLOW}⚠ %d issues${COL_RESET}\n\n" "$ok_count" "$warn_count"
    else
      printf "  ${COL_BRIGHT_GREEN}✓ %d OK${COL_RESET}\n\n" "$ok_count"
    fi
    ;;
  version-all)
    if [[ $# -lt 1 ]]; then
      echo "llmbrains.sh: version-all requires agent definitions JSON" >&2
      usage >&2
      exit 1
    fi
    agents_json=$(read_agent_definitions "$1")
    output_file="${2:-}"

    clear
    printf "${COL_BRIGHT_WHITE}📋 Agents & companion tools — versions & updates${COL_RESET}\n"
    printf "${COL_DIM}%s${COL_RESET}\n" "──────────────────────────────────────────────────"

    # Fetch npm outdated once (columns: Package Current Wanted Latest ...)
    npm_outdated=""
    if command -v npm >/dev/null 2>&1; then
      npm_outdated=$(npm outdated -g 2>/dev/null || true)
    fi

    # Fetch pip outdated once (columns: Package Version Latest Type)
    pip_outdated=""
    if command -v pip >/dev/null 2>&1; then
      pip_outdated=$(pip list --outdated 2>/dev/null || true)
    fi

    ok_count=0
    update_count=0
    warn_count=0
    outdated_ids=""

    while IFS='|' read -r id name command version_args update_hint install_hint; do
      if [[ -n "$id" ]]; then
        version_command="$command $version_args"
        binary="${version_command%% *}"

        if command -v "$binary" >/dev/null 2>&1; then
          read -ra _cmd <<< "$version_command"
          version_output=$("${_cmd[@]}" 2>&1)
          status=$?
          version_first=$(echo "$version_output" | head -1)

          if [[ $status -ne 0 ]]; then
            printf "  ${COL_YELLOW}⚠${COL_RESET}  %-20s ${COL_YELLOW}installed, version check failed${COL_RESET}\n" "$name"
            ((warn_count++))
            continue
          fi

          latest=""
          # Package name is the last non-flag token, e.g. "npm update ... -g @vinhnx/vtcode --registry=..." -> "@vinhnx/vtcode"
          if [[ "$update_hint" == *"npm"* ]]; then
            pkg=$(echo "$update_hint" | awk '{for (i=NF; i>=1; i--) if ($i !~ /^-/) {print $i; exit}}')
            latest=$(echo "$npm_outdated" | awk -v p="$pkg" '$1==p {print $4}')
          elif [[ "$update_hint" == *"pip"* ]]; then
            pkg=$(echo "$update_hint" | awk '{for (i=NF; i>=1; i--) if ($i !~ /^-/) {print $i; exit}}' | tr '[:upper:]' '[:lower:]')
            latest=$(echo "$pip_outdated" | awk -v p="$pkg" 'NR>2 && tolower($1)==p {print $3}')
          fi

          if [[ -n "$latest" ]]; then
            printf "  ${COL_YELLOW}↑${COL_RESET}  %-20s ${COL_DIM}%s${COL_RESET}  →  ${COL_BRIGHT_GREEN}%s available${COL_RESET}\n" "$name" "$version_first" "$latest"
            outdated_ids="${outdated_ids:+$outdated_ids,}$id"
            ((update_count++))
          else
            printf "  ${COL_BRIGHT_GREEN}✓${COL_RESET}  %-20s ${COL_BRIGHT_GREEN}%s${COL_RESET}\n" "$name" "$version_first"
            ((ok_count++))
          fi
        fi
      fi
    done < <(echo "$agents_json" | tr '~' '\n')

    printf "${COL_DIM}%s${COL_RESET}\n" "──────────────────────────────────────────────────"
    if [[ $update_count -gt 0 ]]; then
      printf "  ${COL_BRIGHT_GREEN}✓ %d up to date${COL_RESET}   ${COL_YELLOW}↑ %d updates available${COL_RESET}\n\n" "$ok_count" "$update_count"
    else
      printf "  ${COL_BRIGHT_GREEN}✓ %d up to date${COL_RESET}\n\n" "$ok_count"
    fi
    if [[ -n "$output_file" ]]; then
      echo "uptodate=$ok_count" >> "$output_file"
      echo "updates=$update_count" >> "$output_file"
      echo "outdated_ids=$outdated_ids" >> "$output_file"
      echo "done=1" >> "$output_file"
    fi
    ;;
  update-all)
    if [[ $# -lt 2 ]]; then
      echo "llmbrains.sh: update-all requires agent definitions JSON and active agent IDs" >&2
      usage >&2
      exit 1
    fi
    agents_json=$(read_agent_definitions "$1")
    active_ids="$2"
    output_file="${3:-}"

    if [[ -z "$active_ids" ]]; then
      echo "No agents or companion tools are enabled. Enable them via Preferences > Tools > AgentHub."
      exit 0
    fi

    clear
    printf "${COL_BRIGHT_WHITE}🔄 Updating enabled agents & companion tools${COL_RESET}\n"
    printf "${COL_DIM}%s${COL_RESET}\n" "──────────────────────────────────────────────────"

    ok_count=0
    uptodate_count=0
    fail_count=0
    updated_names=""

    while IFS='|' read -r id name command version_args update_hint install_hint; do
      if [[ -n "$id" ]] && [[ ",$active_ids," == *",$id,"* ]]; then
        "$0" update "$name" "$command" "$update_hint" "$install_hint"
        rc=$?
        if [[ $rc -eq 0 ]]; then
          ((ok_count++))
          updated_names="${updated_names:+$updated_names~}$name"
        elif [[ $rc -eq 2 ]]; then ((uptodate_count++))
        else ((fail_count++)); fi
      fi
    done < <(echo "$agents_json" | tr '~' '\n')

    printf "${COL_DIM}%s${COL_RESET}\n" "──────────────────────────────────────────────────"
    printf "  ${COL_BRIGHT_GREEN}✓ %d updated${COL_RESET}   ${COL_DIM}~ %d up to date${COL_RESET}   ${COL_YELLOW}✗ %d failed${COL_RESET}\n\n" "$ok_count" "$uptodate_count" "$fail_count"
    if [[ -n "$output_file" ]]; then
      echo "ok=$ok_count" >> "$output_file"
      echo "uptodate=$uptodate_count" >> "$output_file"
      echo "failed=$fail_count" >> "$output_file"
      echo "updated_names=$updated_names" >> "$output_file"
      echo "done=1" >> "$output_file"
    fi
    ;;
  detect-all)
    if [[ $# -lt 2 ]]; then
      echo "llmbrains.sh: detect-all requires agent definitions and output file" >&2
      usage >&2
      exit 1
    fi
    agents_json=$(read_agent_definitions "$1")
    output_file="$2"

    clear
    printf "${COL_BRIGHT_WHITE}🔍 Detecting installed agents & companion tools${COL_RESET}\n"
    printf "${COL_DIM}%s${COL_RESET}\n" "──────────────────────────────────────────────────"

    : > "$output_file"  # clear output file

    found_count=0
    missing_count=0

    while IFS='|' read -r id name command version_args install_hint; do
      if [[ -n "$id" ]]; then
        version_command="$command $version_args"
        binary="${version_command%% *}"

        if command -v "$binary" >/dev/null 2>&1; then
          printf "  ${COL_BRIGHT_GREEN}✓${COL_RESET}  %-20s ${COL_BRIGHT_GREEN}installed${COL_RESET}\n" "$name"
          echo "$id=1" >> "$output_file"
          ((found_count++))
        else
          printf "  ${COL_YELLOW}✗${COL_RESET}  %-20s ${COL_DIM}not found${COL_RESET}\n" "$name"
          echo "$id=0" >> "$output_file"
          ((missing_count++))
        fi
      fi
    done < <(echo "$agents_json" | tr '~' '\n')

    printf "${COL_DIM}%s${COL_RESET}\n" "──────────────────────────────────────────────────"
    printf "  ${COL_BRIGHT_GREEN}✓ %d installed${COL_RESET}   ${COL_YELLOW}✗ %d not found${COL_RESET}\n\n" "$found_count" "$missing_count"
    echo "done=1" >> "$output_file"
    ;;
  *)
    echo "llmbrains.sh: unknown subcommand '$subcommand'" >&2
    usage >&2
    exit 1
    ;;
esac
