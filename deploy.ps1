param(
    # Folder of the server, the one that holds the "plugins" folder.
    [Parameter(Mandatory = $true)][string]$ServerPath,
    # Name of the plugin, used to find its jar.
    [string]$Plugin = "AugmentedTrims",
    # Jar to deploy, defaults to the newest build in target.
    [string]$Jar = ""
)

$plugins = Join-Path $ServerPath "plugins"
if (-not (Test-Path $plugins)) {
    throw "No plugins folder at $plugins"
}

if (-not $Jar) {
    $build = Get-ChildItem "$PSScriptRoot\target\$Plugin-*.jar" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $build) {
        throw "No build found in $PSScriptRoot\target, run build.ps1 first"
    }
    $Jar = $build.FullName
}

$version = [regex]::Match((Split-Path $Jar -Leaf), '(\d+\.\d+\.\d+)').Groups[1].Value
if (-not $version) {
    throw "Could not read a version out of $Jar"
}

Write-Host "--- plugins folder before ---"
Get-ChildItem $plugins | Select-Object Name, Length | Format-Table -AutoSize | Out-String | Write-Host

$target = Join-Path $plugins "$Plugin-$version.jar"
Copy-Item $Jar $target -Force
Write-Host "Deployed $target"

# Older versions of the same plugin are clutter: Paper warns about them and may load the wrong
# one. Anything the running server still has open cannot be deleted, that one has to wait.
Get-ChildItem $plugins "$Plugin-*.jar" | Where-Object { $_.Name -ne "$Plugin-$version.jar" } | ForEach-Object {
    try {
        Remove-Item $_.FullName -Force -ErrorAction Stop
        Write-Host "Removed $($_.Name)"
    } catch {
        Write-Host "Kept $($_.Name): the running server still has it open, delete it after the restart"
    }
}

Write-Host "--- plugins folder after ---"
Get-ChildItem $plugins | Select-Object Name, Length | Format-Table -AutoSize | Out-String | Write-Host
