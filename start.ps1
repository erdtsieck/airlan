# Starts the AirLAN server hidden in the background (used by the logon task).
#   .\start.ps1             start the server (unless it is already running)
#   .\start.ps1 -Install    register the logon task and open the firewall (asks for elevation)
#   .\start.ps1 -Uninstall  remove both again
param([switch]$Install, [switch]$Uninstall)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$taskName = 'AirLAN'
$ruleName = 'AirLAN (TCP 8321, local subnet)'
$port = 8321

function Invoke-Elevated([string]$command) {
  Start-Process pwsh -Verb RunAs -Wait -ArgumentList '-NoProfile', '-Command', $command
}

if ($Install) {
  $action = New-ScheduledTaskAction -Execute 'pwsh.exe' -Argument "-NoProfile -WindowStyle Hidden -File `"$root\start.ps1`""
  $trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
  $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero)
  Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Settings $settings -Force | Out-Null
  Write-Host "Logon task '$taskName' registered."

  # This port only, from the local subnet only; also on a home network Windows marked as Public.
  Invoke-Elevated "Remove-NetFirewallRule -DisplayName '$ruleName' -ErrorAction SilentlyContinue; New-NetFirewallRule -DisplayName '$ruleName' -Direction Inbound -Protocol TCP -LocalPort $port -RemoteAddress LocalSubnet -Action Allow -Profile Any | Out-Null"
  Write-Host "Firewall rule '$ruleName' created."

  Start-ScheduledTask -TaskName $taskName
  return
}

if ($Uninstall) {
  Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
  Invoke-Elevated "Remove-NetFirewallRule -DisplayName '$ruleName' -ErrorAction SilentlyContinue"
  Write-Host 'Logon task and firewall rule removed.'
  return
}

$running = Get-CimInstance Win32_Process -Filter "Name='node.exe'" | Where-Object CommandLine -like "*$root\server.js*"
if ($running) { return }

New-Item -ItemType Directory -Force "$root\data" | Out-Null
Start-Process node -ArgumentList "`"$root\server.js`"" -WorkingDirectory $root -WindowStyle Hidden `
  -RedirectStandardOutput "$root\data\server.log" -RedirectStandardError "$root\data\server.err.log"
