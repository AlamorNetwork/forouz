# SPDX-License-Identifier: GPL-3.0-or-later
$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$workspace = Split-Path $repository -Parent
$destination = Join-Path $workspace 'outputs/ForouzEdgeList-Windows'
New-Item -ItemType Directory -Force -Path $destination | Out-Null
$compiler = Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
if (!(Test-Path -LiteralPath $compiler)) { $compiler = Join-Path $env:WINDIR 'Microsoft.NET/Framework/v4.0.30319/csc.exe' }
if (!(Test-Path -LiteralPath $compiler)) { throw 'The Windows .NET Framework C# compiler is required.' }
$portable = Join-Path $destination 'FOROUZ_Edge_List_Windows_1.0.0_Portable.exe'
$setup = Join-Path $destination 'FOROUZ_Edge_List_Windows_1.0.0_Setup.exe'
$catalog = Join-Path $repository 'catalogs/edge-candidates.json'
& $compiler /nologo /target:winexe /platform:anycpu /optimize+ /codepage:65001 /reference:System.Windows.Forms.dll /reference:System.Drawing.dll /reference:System.Net.Http.dll /reference:System.Web.Extensions.dll "/resource:$catalog,forouz.catalog" "/win32manifest:$(Join-Path $PSScriptRoot 'app.manifest')" "/out:$portable" (Join-Path $PSScriptRoot 'EdgeList.cs') (Join-Path $PSScriptRoot 'EdgeScanner.cs')
if ($LASTEXITCODE -ne 0) { throw 'Portable compilation failed.' }
$digest = (Get-FileHash -LiteralPath $portable -Algorithm SHA256).Hash.ToLowerInvariant()
$generated = Join-Path $destination 'ExpectedPayload.cs'
$catalogHash = (Get-FileHash -LiteralPath $catalog -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText($generated, ('namespace ForouzEdgeListSetup { internal static class ExpectedPayload { internal const string Sha256 = "' + $digest + '"; internal const string CatalogSha256 = "' + $catalogHash + '"; } }'), [Text.UTF8Encoding]::new($false))
$readme = Join-Path $PSScriptRoot 'README.fa.md'
$license = Join-Path $repository 'LICENSE'
& $compiler /nologo /target:winexe /platform:anycpu /optimize+ /codepage:65001 /reference:System.Windows.Forms.dll /reference:System.Drawing.dll "/win32manifest:$(Join-Path $PSScriptRoot 'app.manifest')" "/out:$setup" "/resource:$portable,forouz.app" "/resource:$catalog,forouz.catalog" "/resource:$readme,forouz.readme" "/resource:$license,forouz.license" (Join-Path $PSScriptRoot 'Installer.cs') $generated
if ($LASTEXITCODE -ne 0) { throw 'Installer compilation failed.' }
foreach ($item in @(@($portable, 'portable-self-test.json'), @($setup, 'installer-self-test.json'))) {
    $report = Join-Path $destination $item[1]
    $process = Start-Process -FilePath $item[0] -ArgumentList @('--self-test', ('"{0}"' -f $report)) -WindowStyle Hidden -Wait -PassThru
    if ($process.ExitCode -ne 0 -or !(Test-Path -LiteralPath $report)) { throw 'Windows package self-test failed.' }
    $result = Get-Content -LiteralPath $report -Raw | ConvertFrom-Json
    if ($result.failures -ne 0) { throw 'Windows package self-test reported a failure.' }
}
Copy-Item -LiteralPath $readme -Destination (Join-Path $destination 'README.fa.md') -Force
Copy-Item -LiteralPath $license -Destination (Join-Path $destination 'LICENSE.txt') -Force
Copy-Item -LiteralPath $catalog -Destination (Join-Path $destination 'edge-candidates.json') -Force
$zip = Join-Path $destination 'FOROUZ_Edge_List_Windows_1.0.0_Portable.zip'
Compress-Archive -LiteralPath @($portable, (Join-Path $destination 'README.fa.md'), (Join-Path $destination 'LICENSE.txt'), (Join-Path $destination 'edge-candidates.json')) -DestinationPath $zip -Force
$artifacts = @($setup, $zip) | ForEach-Object { @{ name = [IO.Path]::GetFileName($_); bytes = (Get-Item -LiteralPath $_).Length; sha256 = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant() } }
$info = @{ version = '1.0.0'; type = 'offline-edge-scanner-and-ip-list-helper'; vpn_client = $false; signed = $false; artifacts = $artifacts; bundled_catalog_sha256 = $catalogHash; portable_checks = (Get-Content (Join-Path $destination 'portable-self-test.json') -Raw | ConvertFrom-Json); installer_check = (Get-Content (Join-Path $destination 'installer-self-test.json') -Raw | ConvertFrom-Json); not_run = @('Interactive Windows installation, Start menu launch, real remote TCP/TLS scans and cancellation, refresh failure fallback, clipboard, file dialogs and display scaling on other machines') }
[IO.File]::WriteAllText((Join-Path $destination 'BUILD-WINDOWS.json'), ($info | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
$artifacts | ForEach-Object { Write-Output ($_.name + ' ' + $_.bytes + ' bytes') }
