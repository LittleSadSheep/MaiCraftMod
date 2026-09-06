param(
    [string]$Model = 'lightnav-0-visible',
    [int]$Port = 8050
)

$ErrorActionPreference = 'Stop'
$bridgeScript = Join-Path $PSScriptRoot 'bridge.py'
$venvPython = Join-Path $PSScriptRoot '.venv\Scripts\python.exe'
$bridgePython = if (Test-Path -LiteralPath $venvPython) {
    $venvPython
} else {
    (Get-Command python -ErrorAction Stop).Source
}

Write-Host "LM Studio must have '$Model' loaded and its local server running on port 1234."
Write-Host 'After installing the new MaiCraft JAR, use /maicraft lightnav observe <instruction>.'
& $bridgePython $bridgeScript --model $Model --port $Port
exit $LASTEXITCODE
