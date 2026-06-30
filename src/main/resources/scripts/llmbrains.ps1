# MIRROR: llmbrains.sh -- any logic change here must be mirrored in the bash script
param(
    [Parameter(Mandatory=$true)]
    [string]$Subcommand,

    [Parameter(ValueFromRemainingArguments=$true)]
    [string[]]$Arguments
)

$ErrorActionPreference = "Stop"

function Show-Usage {
    Write-Host @"
Usage:
  llmbrains.ps1 check <friendly-name> <version-command> [install-hint]
  llmbrains.ps1 update <friendly-name> <binary> <update-command> [install-hint]
  llmbrains.ps1 check-all <agent-definitions>
  llmbrains.ps1 version-all <agent-definitions-file>
  llmbrains.ps1 update-all <agent-definitions-file> <active-agent-ids>
  llmbrains.ps1 detect-all <agent-definitions-file> <output-file>
"@
}

# version-all/update-all/detect-all receive their agent-definitions payload via a temp file
# (rather than inline on the command line) because the payload contains raw `"`, `$`, and `|`
# characters (e.g. forge/goose/plandex install hints) that PowerShell mangles when passed as
# arguments to a native executable. The caller deletes nothing; this function consumes the file.
function Read-AgentDefinitions {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        Write-Error "Agent definitions file not found: $Path"
        exit 1
    }
    $data = Get-Content -LiteralPath $Path -Raw -Encoding UTF8
    Remove-Item -LiteralPath $Path -ErrorAction SilentlyContinue
    return $data.TrimEnd("`r", "`n")
}

function Invoke-Check {
    param($Name, $VersionCommand, $InstallHint)

    $binary = $VersionCommand -split ' ' | Select-Object -First 1
    if ([string]::IsNullOrEmpty($binary)) {
        Write-Error "Could not determine binary for $Name"
        return $false
    }

    if (Get-Command $binary -ErrorAction SilentlyContinue) {
        try {
            $versionOutput = (cmd /c "$VersionCommand 2>&1") | Select-Object -First 1 | Out-String
            Write-Host ("  [+] {0,-20} " -f $Name) -NoNewline
            Write-Host $versionOutput.Trim() -ForegroundColor Green
            return $true
        } catch {
            Write-Host ("  [!] {0,-20} " -f $Name) -NoNewline
            Write-Host "installed, version check failed" -ForegroundColor Yellow
            return $false
        }
    } else {
        Write-Host ("  [x] {0,-20} " -f $Name) -NoNewline
        if ($InstallHint) {
            Write-Host ("not installed  ->  {0}" -f $InstallHint) -ForegroundColor Yellow
        } else {
            Write-Host "not installed" -ForegroundColor Yellow
        }
        return $false
    }
}

function Invoke-Update {
    param($Name, $Binary, $UpdateCommand, $InstallHint)

    if (Get-Command $Binary -ErrorAction SilentlyContinue) {
        Write-Host ("  [~] {0}" -f $Name) -ForegroundColor White
        $updateOutput = (cmd /c "$UpdateCommand 2>&1") -join "`n"
        $pipNoise = 'Collecting |Downloading |Installing collected|Using cached |Building wheels|Obtaining |Attempting|Running command|Getting requirements|Preparing metadata|Requirement already satisfied|Uninstalling |Successfully uninstalled|Found existing installation|which is incompatible|\[notice\]|WARNING|NOTE|DEPRECATION|ERROR: pip'
        $displayLines = $updateOutput -split "`n" | Where-Object { $_ -notmatch $pipNoise -and $_.Trim() -ne "" }
        if ($displayLines) {
            $displayLines | ForEach-Object { Write-Host ("     {0}" -f $_.TrimEnd()) }
        }
        if ($LASTEXITCODE -eq 0 -or $updateOutput -match "Successfully installed") {
            if ($updateOutput -match "up to date|Requirement already satisfied|already up-to-date|already installed") {
                Write-Host ("  [~] {0,-20} " -f $Name) -NoNewline
                Write-Host "up to date" -ForegroundColor DarkGray
                Write-Host ""
                return 2
            } else {
                Write-Host ("  [+] {0,-20} " -f $Name) -NoNewline
                Write-Host "updated" -ForegroundColor Green
                Write-Host ""
                return 0
            }
        } else {
            Write-Host ("  [x] {0,-20} " -f $Name) -NoNewline
            Write-Host "update failed" -ForegroundColor Yellow
            Write-Host ""
            return 1
        }
    } else {
        $hint = if ($InstallHint) { $InstallHint } else { "install instructions unavailable" }
        Write-Host ("  [x] {0,-20} " -f $Name) -NoNewline
        Write-Host ("not installed  ->  {0}" -f $hint) -ForegroundColor Yellow
        Write-Host ""
        return 1
    }
}

switch ($Subcommand) {
    "check" {
        if ($Arguments.Count -lt 2) {
            Write-Error "check requires a name and version command"
            Show-Usage
            exit 1
        }
        $name = $Arguments[0]
        $versionCommand = $Arguments[1]
        $installHint = if ($Arguments.Count -gt 2) { $Arguments[2] } else { "" }
        Invoke-Check -Name $name -VersionCommand $versionCommand -InstallHint $installHint
    }

    "update" {
        if ($Arguments.Count -lt 3) {
            Write-Error "update requires a name, binary, and update command"
            Show-Usage
            exit 1
        }
        $name = $Arguments[0]
        $binary = $Arguments[1]
        $updateCommand = $Arguments[2]
        $installHint = if ($Arguments.Count -gt 3) { $Arguments[3] } else { "" }
        Invoke-Update -Name $name -Binary $binary -UpdateCommand $updateCommand -InstallHint $installHint
    }

    "check-all" {
        if ($Arguments.Count -lt 1) {
            Write-Error "check-all requires agent definitions"
            Show-Usage
            exit 1
        }
        $agentsData = $Arguments[0]

        Clear-Host
        Write-Host "[?] Checking agents & companion tools" -ForegroundColor White
        Write-Host ("-" * 50) -ForegroundColor DarkGray

        $okCount = 0
        $warnCount = 0

        $agentsData -split "~" | ForEach-Object {
            $parts = $_ -split '\|', 4
            if ($parts.Count -ge 3 -and $parts[0]) {
                $name = $parts[0]
                $command = $parts[1]
                $versionArgs = $parts[2]
                $installHint = if ($parts.Count -gt 3) { $parts[3] } else { "" }
                $versionCommand = "$command $versionArgs".Trim()
                $ok = Invoke-Check -Name $name -VersionCommand $versionCommand -InstallHint $installHint
                if ($ok) { $okCount++ } else { $warnCount++ }
            }
        }

        Write-Host ("-" * 50) -ForegroundColor DarkGray
        if ($warnCount -gt 0) {
            Write-Host ("  [+] {0} OK" -f $okCount) -NoNewline -ForegroundColor Green
            Write-Host ("   [!] {0} issues" -f $warnCount) -ForegroundColor Yellow
        } else {
            Write-Host ("  [+] {0} OK" -f $okCount) -ForegroundColor Green
        }
        Write-Host ""
    }

    "version-all" {
        if ($Arguments.Count -lt 1) {
            Write-Error "version-all requires agent definitions"
            Show-Usage
            exit 1
        }
        $agentsData = Read-AgentDefinitions -Path $Arguments[0]
        $outputFile = if ($Arguments.Count -gt 1) { $Arguments[1] } else { "" }

        Clear-Host
        Write-Host "[?] Agents & companion tools - versions & updates" -ForegroundColor White
        Write-Host ("-" * 50) -ForegroundColor DarkGray

        # Fetch npm outdated once
        $npmOutdated = ""
        if (Get-Command npm -ErrorAction SilentlyContinue) {
            $npmOutdated = (cmd /c "npm outdated -g 2>nul") -join "`n"
        }

        # Fetch pip outdated once
        $pipOutdated = ""
        if (Get-Command pip -ErrorAction SilentlyContinue) {
            $pipOutdated = (cmd /c "pip list --outdated 2>nul") -join "`n"
        }

        $okCount = 0
        $updateCount = 0
        $warnCount = 0
        $outdatedIds = @()

        $agentsData -split "~" | ForEach-Object {
            $parts = $_ -split '\|', 6
            if ($parts.Count -ge 3 -and $parts[0]) {
                $id = $parts[0]
                $name = $parts[1]
                $command = $parts[2]
                $versionArgs = $parts[3]
                $updateHint = if ($parts.Count -gt 4) { $parts[4] } else { "" }
                $versionCommand = "$command $versionArgs".Trim()

                if (Get-Command $command -ErrorAction SilentlyContinue) {
                    try {
                        $versionFirst = ((cmd /c "$versionCommand 2>&1") | Select-Object -First 1 | Out-String).Trim()

                        # Package name is the last non-flag token, e.g. "npm update ... -g @vinhnx/vtcode --registry=..." -> "@vinhnx/vtcode"
                        $latest = ""
                        if ($updateHint -match "npm") {
                            $pkg = $updateHint -split '\s+' | Where-Object { $_ -notmatch '^-' } | Select-Object -Last 1
                            $line = $npmOutdated -split "`n" | Where-Object { $_ -match "^$pkg\s" } | Select-Object -First 1
                            if ($line) {
                                $cols = ($line -split '\s+' | Where-Object { $_ })
                                if ($cols.Count -ge 4) { $latest = $cols[3] }
                            }
                        } elseif ($updateHint -match "pip") {
                            $pkg = ($updateHint -split '\s+' | Where-Object { $_ -notmatch '^-' } | Select-Object -Last 1).ToLower()
                            $line = $pipOutdated -split "`n" | Where-Object { $_.ToLower() -match "^$pkg\s" } | Select-Object -First 1
                            if ($line) {
                                $cols = ($line -split '\s+' | Where-Object { $_ })
                                if ($cols.Count -ge 3) { $latest = $cols[2] }
                            }
                        }

                        if ($latest) {
                            Write-Host ("  [^] {0,-20} " -f $name) -NoNewline -ForegroundColor Yellow
                            Write-Host ("{0}  ->  {1} available" -f $versionFirst, $latest) -ForegroundColor Yellow
                            $outdatedIds += $id
                            $updateCount++
                        } else {
                            Write-Host ("  [+] {0,-20} " -f $name) -NoNewline
                            Write-Host $versionFirst -ForegroundColor Green
                            $okCount++
                        }
                    } catch {
                        Write-Host ("  [!] {0,-20} " -f $name) -NoNewline
                        Write-Host "installed, version check failed" -ForegroundColor Yellow
                        $warnCount++
                    }
                }
            }
        }

        Write-Host ("-" * 50) -ForegroundColor DarkGray
        if ($updateCount -gt 0) {
            Write-Host ("  [+] {0} up to date" -f $okCount) -NoNewline -ForegroundColor Green
            Write-Host ("   [^] {0} updates available" -f $updateCount) -ForegroundColor Yellow
        } else {
            Write-Host ("  [+] {0} up to date" -f $okCount) -ForegroundColor Green
        }
        Write-Host ""
        if ($outputFile) {
            "uptodate=$okCount" | Out-File -FilePath $outputFile -Append -Encoding ascii
            "updates=$updateCount" | Out-File -FilePath $outputFile -Append -Encoding ascii
            "outdated_ids=$($outdatedIds -join ',')" | Out-File -FilePath $outputFile -Append -Encoding ascii
            "done=1" | Out-File -FilePath $outputFile -Append -Encoding ascii
        }
    }

    "update-all" {
        if ($Arguments.Count -lt 2) {
            Write-Error "update-all requires agent definitions and active agent IDs"
            Show-Usage
            exit 1
        }
        $agentsData = Read-AgentDefinitions -Path $Arguments[0]
        $activeIds = $Arguments[1]
        $outputFile = if ($Arguments.Count -gt 2) { $Arguments[2] } else { "" }

        if ([string]::IsNullOrEmpty($activeIds)) {
            Write-Host "No agents or companion tools are enabled. Enable them via Preferences > Tools > AgentHub."
            exit 0
        }

        Clear-Host
        Write-Host "[~] Updating enabled agents & companion tools" -ForegroundColor White
        Write-Host ("-" * 50) -ForegroundColor DarkGray

        $okCount = 0
        $uptodateCount = 0
        $failCount = 0
        $updatedNames = @()

        $activeIdsList = ",$activeIds,"
        $agentsData -split "~" | ForEach-Object {
            $parts = $_ -split '\|', 6
            if ($parts.Count -ge 5 -and $parts[0]) {
                $id = $parts[0]
                if ($activeIdsList -like "*,$id,*") {
                    $name = $parts[1]
                    $command = $parts[2]
                    $updateHint = $parts[4]
                    $installHint = if ($parts.Count -gt 5) { $parts[5] } else { "" }
                    $result = Invoke-Update -Name $name -Binary $command -UpdateCommand $updateHint -InstallHint $installHint
                    if ($result -eq 2) { $uptodateCount++ }
                    elseif ($result -eq 0) { $okCount++; $updatedNames += $name }
                    else { $failCount++ }
                }
            }
        }

        Write-Host ("-" * 50) -ForegroundColor DarkGray
        Write-Host ("  [+] {0} updated" -f $okCount) -NoNewline -ForegroundColor Green
        Write-Host ("   [~] {0} up to date" -f $uptodateCount) -NoNewline -ForegroundColor DarkGray
        Write-Host ("   [x] {0} failed" -f $failCount) -ForegroundColor Yellow
        Write-Host ""
        if ($outputFile) {
            "ok=$okCount" | Out-File -FilePath $outputFile -Append -Encoding ascii
            "uptodate=$uptodateCount" | Out-File -FilePath $outputFile -Append -Encoding ascii
            "failed=$failCount" | Out-File -FilePath $outputFile -Append -Encoding ascii
            "updated_names=$($updatedNames -join '~')" | Out-File -FilePath $outputFile -Append -Encoding ascii
            "done=1" | Out-File -FilePath $outputFile -Append -Encoding ascii
        }
    }

    "detect-all" {
        if ($Arguments.Count -lt 2) {
            Write-Error "detect-all requires agent definitions and output file"
            Show-Usage
            exit 1
        }
        $agentsData = Read-AgentDefinitions -Path $Arguments[0]
        $outputFile = $Arguments[1]

        Clear-Host
        Write-Host "[?] Detecting installed agents & companion tools" -ForegroundColor White
        Write-Host ("-" * 50) -ForegroundColor DarkGray

        "" | Out-File -FilePath $outputFile -Encoding ascii

        $foundCount = 0
        $missingCount = 0

        $agentsData -split "~" | ForEach-Object {
            $parts = $_ -split '\|', 5
            if ($parts.Count -ge 3 -and $parts[0]) {
                $id = $parts[0]
                $name = $parts[1]
                $command = $parts[2]

                if (Get-Command $command -ErrorAction SilentlyContinue) {
                    Write-Host ("  [+] {0,-20} " -f $name) -NoNewline
                    Write-Host "installed" -ForegroundColor Green
                    "$id=1" | Out-File -FilePath $outputFile -Append -Encoding ascii
                    $foundCount++
                } else {
                    Write-Host ("  [x] {0,-20} " -f $name) -NoNewline
                    Write-Host "not found" -ForegroundColor DarkGray
                    "$id=0" | Out-File -FilePath $outputFile -Append -Encoding ascii
                    $missingCount++
                }
            }
        }

        Write-Host ("-" * 50) -ForegroundColor DarkGray
        Write-Host ("  [+] {0} installed" -f $foundCount) -NoNewline -ForegroundColor Green
        Write-Host ("   [x] {0} not found" -f $missingCount) -ForegroundColor DarkYellow
        Write-Host ""
        "done=1" | Out-File -FilePath $outputFile -Append -Encoding ascii
    }

    default {
        Write-Error "Unknown subcommand: $Subcommand"
        Show-Usage
        exit 1
    }
}
