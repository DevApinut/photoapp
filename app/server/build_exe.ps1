$ErrorActionPreference = "Stop"
$serverDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$runtimePython = "C:\Users\HP\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe"
$sitePackages = Join-Path $serverDir ".build-packages"

if (-not (Test-Path -LiteralPath $runtimePython)) {
    throw "Bundled build Python was not found: $runtimePython"
}

& $runtimePython -m pip install --upgrade --target $sitePackages `
    "pyinstaller==6.16.0" `
    "fastapi==0.116.1" `
    "uvicorn[standard]==0.35.0" `
    "python-multipart==0.0.20"

$env:PYTHONPATH = $sitePackages
& $runtimePython -m PyInstaller `
    --noconfirm `
    --clean `
    --onefile `
    --console `
    --name "DN-Photo-Server-V5" `
    --paths $serverDir `
    --collect-all uvicorn `
    --collect-all fastapi `
    --hidden-import app `
    --distpath $serverDir `
    --workpath (Join-Path $serverDir "build-v5") `
    --specpath $serverDir `
    (Join-Path $serverDir "launcher.py")

Write-Host ""
Write-Host "Created: $serverDir\DN-Photo-Server-V5.exe"
