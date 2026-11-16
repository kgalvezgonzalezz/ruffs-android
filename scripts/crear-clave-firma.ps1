<#
  Crea la clave de firma de RUFFS para Android (una sola vez).

  - La clave queda en  %USERPROFILE%\.ruffs-firma\ruffs-release.jks  (fuera del repositorio).
  - La contraseña la escribes tú; no se muestra en pantalla.
  - Se guarda la configuración en keystore.properties (en la raíz del proyecto,
    ignorado por git) para que Gradle firme las versiones "release".

  IMPORTANTE: guarda una copia de ruffs-release.jks y de la contraseña (por
  ejemplo en tu gestor de contraseñas). Si se pierden, las nuevas versiones de la
  app no podrán instalarse encima de las anteriores.
#>
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$dir = Join-Path $env:USERPROFILE '.ruffs-firma'
$jks = Join-Path $dir 'ruffs-release.jks'
$props = Join-Path $root 'keystore.properties'
$keytool = 'C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe'
if (-not (Test-Path $keytool)) { $keytool = 'keytool' }

Write-Host ''
Write-Host '== Clave de firma de RUFFS para Android ==' -ForegroundColor Cyan
if (Test-Path $jks) {
    Write-Host "Ya existe una clave en $jks. No se toca (borrarla impediría actualizar la app)." -ForegroundColor Yellow
    exit 1
}

function Read-Plain([string]$prompt) {
    $sec = Read-Host -Prompt $prompt -AsSecureString
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
}

while ($true) {
    $p1 = Read-Plain 'Contraseña nueva para la clave (mínimo 8 caracteres)'
    if ($p1.Length -lt 8) { Write-Host 'Demasiado corta.' -ForegroundColor Yellow; continue }
    $p2 = Read-Plain 'Repite la contraseña'
    if ($p1 -ne $p2) { Write-Host 'No coinciden, prueba otra vez.' -ForegroundColor Yellow; continue }
    break
}

New-Item -ItemType Directory -Force $dir | Out-Null
$env:RUFFS_KS_PASS = $p1
try {
    & $keytool -genkeypair -v -keystore $jks -storetype PKCS12 -alias ruffs `
        -keyalg RSA -keysize 4096 -validity 36500 -dname 'CN=RUFFS, O=RUFFS' `
        -storepass:env RUFFS_KS_PASS -keypass:env RUFFS_KS_PASS | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'keytool falló' }
} finally {
    Remove-Item Env:RUFFS_KS_PASS -ErrorAction SilentlyContinue
}

$storeFile = $jks -replace '\\', '/'
$escaped = $p1 -replace '\\', '\\\\'
$content = "storeFile=$storeFile`nstorePassword=$escaped`nkeyAlias=ruffs`nkeyPassword=$escaped`n"
[IO.File]::WriteAllText($props, $content, (New-Object Text.UTF8Encoding $false))
$p1 = $null; $p2 = $null

Write-Host ''
Write-Host "Clave creada:  $jks" -ForegroundColor Green
Write-Host "Configuración: $props (git la ignora)" -ForegroundColor Green
Write-Host 'Guarda una copia de la clave y de la contraseña en un lugar seguro.' -ForegroundColor Yellow
Write-Host 'LISTO' -ForegroundColor Green
