# T268: Ctrl-C from the launching console must reach the GUI-subsystem exe (T224/T226).
# A `windows_subsystem = "windows"` process starts with no console, so only AttachConsole(ATTACH_PARENT_PROCESS)
# puts it on a console: without that call the CTRL_C_EVENT below never reaches the ctrlc handler, the process
# keeps running, and this script fails on the timeout. Exit code 0 is what the handler's flag-unwind produces;
# an unhandled Ctrl-C would terminate with 0xC000013A instead.
param([Parameter(Mandatory = $true)][string]$Exe)

Add-Type @'
using System;
using System.Runtime.InteropServices;

public static class Native {
    [DllImport("kernel32.dll", SetLastError = true)] public static extern bool FreeConsole();
    [DllImport("kernel32.dll", SetLastError = true)] public static extern bool AllocConsole();
    [DllImport("kernel32.dll", SetLastError = true)] public static extern bool SetConsoleCtrlHandler(IntPtr handler, bool add);
    [DllImport("kernel32.dll", SetLastError = true)] public static extern bool GenerateConsoleCtrlEvent(uint ctrlEvent, uint processGroupId);
}
'@

$ErrorActionPreference = "Stop"

$exe = (Resolve-Path -LiteralPath $Exe).Path

# Private console: the event then reaches only this script and the app, never the CI runner's processes.
[void][Native]::FreeConsole()
if (-not [Native]::AllocConsole()) {
    throw "AllocConsole failed (error $([System.Runtime.InteropServices.Marshal]::GetLastWin32Error()))"
}

$psi = [System.Diagnostics.ProcessStartInfo]::new($exe)
$psi.UseShellExecute = $false # keep the child's parent this process, so AttachConsole finds this console
$psi.RedirectStandardError = $true
$psi.RedirectStandardOutput = $true
$p = [System.Diagnostics.Process]::Start($psi)
if ($null -eq $p) { throw "could not start $exe" }
try {
    # After CreateProcess: the "ignore Ctrl-C" attribute is inherited at creation and must not reach the app.
    [void][Native]::SetConsoleCtrlHandler([IntPtr]::Zero, $true)
    Start-Sleep -Seconds 2 # let the app create its window and install the handler
    if ($p.HasExited) {
        throw "gimlistream exited before Ctrl-C (exit code $($p.ExitCode)); stderr: $($p.StandardError.ReadToEnd())"
    }

    # 0 = CTRL_C_EVENT, group 0 = every process attached to this console. Repeat in case the first event
    # lands before AttachConsole; once the handler sets QUIT the app exits and the loop stops.
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    while (-not $p.HasExited -and [DateTime]::UtcNow -lt $deadline) {
        [void][Native]::GenerateConsoleCtrlEvent(0, 0)
        Start-Sleep -Milliseconds 250
    }
    if (-not $p.WaitForExit(5000)) {
        throw "gimlistream did not exit on Ctrl-C: is it missing AttachConsole or the ctrlc handler?"
    }

    $stderr = $p.StandardError.ReadToEnd()
    $stdout = $p.StandardOutput.ReadToEnd()
    if ($stderr -match "panicked") { throw "panic after Ctrl-C:`n$stderr" }
    if ($p.ExitCode -ne 0) {
        throw "Ctrl-C gave exit code $($p.ExitCode), expected 0`nstderr: $stderr`nstdout: $stdout"
    }
    Write-Host "Ctrl-C smoke OK: exit 0 (no panic)"
} finally {
    if (-not $p.HasExited) {
        try { $p.Kill() } catch {}
        [void]$p.WaitForExit(5000)
    }
}
