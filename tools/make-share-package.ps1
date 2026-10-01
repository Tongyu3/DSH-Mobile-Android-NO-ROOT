# 生成"分享包"（给使用者直接下载的那一个 zip）。
#
# 之前这一步是临时敲的命令，容易漏文件（比如忘了 shizuku\ 里的 APK，
# 或者把签名密钥顺手打进去）。这里固化成脚本，并带上敏感文件断言。
#
# 用法（在仓库根目录）:
#   pwsh -File tools\make-share-package.ps1 -Version 0.2.0
#
# 输入:
#   DshMobile\app\build\outputs\apk\release\app-release.apk   必须是已签名的 release 包
#   分享\*.md 分享\img 分享\shizuku
#
# 产出:
#   分享\DeepSeek-Harness-<Version>.apk
#   分享\DeepSeek-Harness-<Version>-分享包.zip

param(
    [Parameter(Mandatory = $true)][string]$Version
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$share = Join-Path $root '分享'
$apkName = "DeepSeek-Harness-$Version.apk"
$zip = Join-Path $share "DeepSeek-Harness-$Version-分享包.zip"

Write-Host '── 1/4 取 release APK ──'
$built = Join-Path $root 'DshMobile\app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path $built)) { throw "还没有 release APK: $built" }
$apk = Join-Path $share $apkName
Copy-Item $built $apk -Force
Write-Host ("  {0}  ({1:N0} 字节)" -f $apkName, (Get-Item $apk).Length)

Write-Host '── 2/4 拼装 staging ──'
$stage = Join-Path $env:TEMP "dsh-share-$Version"
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Path $stage -Force | Out-Null

Copy-Item $apk $stage
foreach ($p in @('安装与使用说明.md', 'Shizuku授权教程.md', '插件市场说明.md')) {
    $from = Join-Path $share $p
    if (-not (Test-Path $from)) { throw "分享\ 里缺少: $p" }
    Copy-Item $from $stage
    Write-Host "  $p"
}
foreach ($d in @('img', 'shizuku')) {
    $from = Join-Path $share $d
    if (-not (Test-Path $from)) { throw "分享\ 里缺少目录: $d" }
    Copy-Item $from $stage -Recurse
    Write-Host "  $d\"
}

Write-Host '── 3/4 敏感文件检查 ──'
$leaks = Get-ChildItem $stage -Recurse -File |
    Where-Object { $_.Name -match 'release\.keystore|keystore\.properties|\.jks$|\.keystore$|\.bak$' }
if ($leaks) {
    $leaks | ForEach-Object { Write-Host "  !! $($_.FullName)" }
    throw '分享包里出现了签名密钥或备份文件，已中止'
}
Write-Host '  OK：没有密钥/备份文件'

Write-Host '── 4/4 打包 ──'
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $zip -Force
$z = Get-Item $zip
Write-Host ("  完成: {0}  ({1:N0} 字节)" -f $z.Name, $z.Length)
Remove-Item $stage -Recurse -Force
