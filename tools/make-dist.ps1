# Builds a customer package: dist\APIQualityStudio-<version>\  (bundled Java runtime when jpackage is available) and a zip.
param([string]$Version = "1.0.0", [string]$CertThumbprint = "", [string]$TimestampUrl = "http://timestamp.digicert.com")
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$out = Join-Path $root "dist\APIQualityStudio-$Version"
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force "$out" | Out-Null
$stage = Join-Path $root "dist\_stage"
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
New-Item -ItemType Directory -Force "$stage" | Out-Null

Write-Host "1/5 compile and collect libraries"
mvn -q -DskipTests compile dependency:copy-dependencies "-DoutputDirectory=$stage" | Out-Null
Write-Host "2/5 product jar"
$jdk = (Get-ChildItem "C:\Program Files\Java" -Directory -Filter "jdk-*" | Sort-Object Name -Descending | Select-Object -First 1).FullName
if (-not $jdk) { throw "A JDK (17+) is needed to build the package." }
& "$jdk\bin\jar.exe" --create --file "$stage\product.jar" --main-class com.api.product.studio.StudioMain -C target\classes .

Write-Host "3/5 bundled runtime (jpackage app-image)"
$jp = "$jdk\bin\jpackage.exe"
if (Test-Path $jp) {
  & $jp --type app-image --name APIQualityStudio --input $stage --main-jar product.jar --main-class com.api.product.studio.StudioMain `
      --dest $out --win-console --add-launcher "studio-cli=tools\cli-launcher.properties" --java-options "-Dstudio.open=true" --java-options "-Dstudio.port=8099" --app-version $Version | Out-Null
} else { throw "jpackage not found in $jdk" }

Write-Host "4/5 launchers and documents"
@'
@echo off
rem API Quality Studio - starts the local app. Your projects and reports are kept in %USERPROFILE%\APIQualityStudio-data
set DATA=%USERPROFILE%\APIQualityStudio-data
if not exist "%DATA%" mkdir "%DATA%"
cd /d "%DATA%"
"%~dp0APIQualityStudio\APIQualityStudio.exe"
'@ | Set-Content -Encoding ASCII "$out\Start API Quality Studio.bat"
@'
@echo off
rem Command line for build pipelines. Example:  studio-cli run MyProject --min-pass-rate 90 --junit studio-junit.xml
set DATA=%USERPROFILE%\APIQualityStudio-data
if not exist "%DATA%" mkdir "%DATA%"
cd /d "%DATA%"
"%~dp0APIQualityStudio\studio-cli.exe" %*
exit /b %errorlevel%
'@ | Set-Content -Encoding ASCII "$out\studio-cli.bat"
Copy-Item docs\User_Manual.pdf "$out\User_Manual.pdf" -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$out\samples" | Out-Null
Copy-Item sample_apis\specs\* "$out\samples\" -Recurse
Copy-Item docs\templates\* "$out\samples\" -ErrorAction SilentlyContinue
@"
API Quality Studio $Version
===========================
1. Double-click "Start API Quality Studio.bat". The app opens in your browser (http://localhost:8099).
2. Press "Try the demo" to see the whole flow, or "+ New project" for your own API.
3. First start is a 30-day trial. Install your licence under "Licence" in the left menu.
4. User_Manual.pdf explains everything. samples\ holds sample API files and BRD templates.

Everything runs on this computer. Nothing is sent anywhere. Use it only on systems you are authorised to test.
Projects and reports are stored in %USERPROFILE%\APIQualityStudio-data.
Pipelines: studio-cli.bat run PROJECT --min-pass-rate 90 --junit studio-junit.xml (exit code 0 = passed).
"@ | Set-Content -Encoding ASCII "$out\README-FIRST.txt"

if (Get-ChildItem $out -Recurse -Include "license-private.key","*private*.key" -ErrorAction SilentlyContinue) { throw "A licence private key is inside the package. Remove it before shipping." }
if ($CertThumbprint) {
  Write-Host "signing executables with certificate $CertThumbprint (from the Windows certificate store)"
  $st = Get-ChildItem "C:\Program Files (x86)\Windows Kits\10\bin" -Recurse -Filter signtool.exe -ErrorAction SilentlyContinue | Where-Object { $_.FullName -match "x64" } | Sort-Object FullName -Descending | Select-Object -First 1
  if ($st) {
    Get-ChildItem $out -Recurse -Include *.exe,*.dll | ForEach-Object { & $st.FullName sign /sha1 $CertThumbprint /fd SHA256 /tr $TimestampUrl /td SHA256 $_.FullName | Out-Null; if ($LASTEXITCODE -ne 0) { throw "signing failed for $($_.Name)" } }
  } else {
    Write-Host "signtool.exe not found - using PowerShell Set-AuthenticodeSignature instead"
    $cert = Get-ChildItem Cert:\CurrentUser\My, Cert:\LocalMachine\My -ErrorAction SilentlyContinue | Where-Object { $_.Thumbprint -eq $CertThumbprint } | Select-Object -First 1
    if (-not $cert) { throw "Certificate $CertThumbprint not found in the Windows certificate store." }
    Get-ChildItem $out -Recurse -Include *.exe,*.dll | ForEach-Object {
      $sig = Set-AuthenticodeSignature -FilePath $_.FullName -Certificate $cert -HashAlgorithm SHA256 -TimestampServer $TimestampUrl
      if (-not $sig.SignerCertificate -or $sig.SignerCertificate.Thumbprint -ne $cert.Thumbprint) { throw "signing failed for $($_.Name): $($sig.StatusMessage)" }
    }
  }
} else { Write-Host "NOTE: package is UNSIGNED (pass -CertThumbprint <thumbprint> once a code-signing certificate is installed)." }

Write-Host "5/5 zip"
$zip = Join-Path $root "dist\APIQualityStudio-$Version.zip"
if (Test-Path $zip) { Remove-Item $zip }
Compress-Archive -Path "$out\*" -DestinationPath $zip
Remove-Item -Recurse -Force $stage
Write-Host ("Done: " + $zip + "  (" + [math]::Round((Get-Item $zip).Length/1MB) + " MB)")
