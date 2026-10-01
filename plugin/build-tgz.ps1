# 把 plugin/ 下的插件打成 npm tarball，供 APK 内置。
#
# 为什么要有这个脚本：tarball 之前是手工打的，而"手工步骤"在这个项目里
# 已经出过事（改了 patch.js 忘了同步 assets）。打包决定产物内容，
# 必须是一条命令、可重复。
#
# 用法（仓库根目录）:
#   pwsh -File plugin\build-tgz.ps1
#
# 产出: DshMobile\app\src\main\assets\<包名>-<版本>.tgz  （每个插件一个）
# 注意: 版本号变了之后，还要同步改 Env.java 里的 PHONE_PLUGIN_TGZ /
#       PHONE_FILES_PLUGIN_TGZ —— 那是"是否已安装"的判据，
#       用它判重才能让 App 升级带动插件升级。

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$assets = Join-Path $root 'DshMobile\app\src\main\assets'

# 每个插件：源目录 + 要打进包里的文件（相对源目录）。
# 注意 lib\client.js 是**浏览器侧**的 bundle（DSH 用 window.__ModuleLoader__ 加载），
# 它必须列在这里 —— 少了它插件装上去只有宿主侧，界面上什么都不会出现。
$plugins = @(
    @{ dir = 'dsh-phone';       files = @('package.json', 'index.js', 'cordis.patch.yml', 'README.md') },
    @{ dir = 'dsh-phone-files'; files = @('package.json', 'lib\index.js', 'lib\client.js', 'cordis.patch.yml', 'README.md') }
)

$stamped = @()
foreach ($p in $plugins) {
    $src = Join-Path $root ('plugin\' + $p.dir)
    if (-not (Test-Path $src)) { throw "缺少插件目录: $src" }
    $pkg = Get-Content (Join-Path $src 'package.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $name = $pkg.name
    $version = $pkg.version
    $file = "$name-$version.tgz"
    Write-Host "打包 $name@$version"

    # npm tarball 的约定：包内容必须放在 `package/` 目录下
    $stage = Join-Path $env:TEMP ("dshpkg-" + $p.dir + "-" + $version)
    if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
    New-Item -ItemType Directory (Join-Path $stage 'package') -Force | Out-Null
    foreach ($f in $p.files) {
        $from = Join-Path $src $f
        if (-not (Test-Path $from)) { throw "$($p.dir) 里缺少 $f" }
        $to = Join-Path (Join-Path $stage 'package') $f
        New-Item -ItemType Directory (Split-Path $to -Parent) -Force | Out-Null
        Copy-Item $from $to -Force
    }

    $tgz = Join-Path $root ('plugin\' + $file)
    if (Test-Path $tgz) { Remove-Item $tgz -Force }
    & tar.exe -czf $tgz -C $stage package
    if (-not (Test-Path $tgz)) { throw "打包失败: $file" }

    # 旧的同名 tgz 必须清掉：留着会被误以为仍然生效
    Get-ChildItem $assets -Filter ("$name-*.tgz") | Where-Object { $_.Name -ne $file } | ForEach-Object {
        Write-Host "  移除旧包 $($_.Name)"
        Remove-Item $_.FullName -Force
    }
    Copy-Item $tgz (Join-Path $assets $file) -Force
    $size = (Get-Item (Join-Path $assets $file)).Length
    Write-Host "  完成: assets\$file ($size 字节)"
    $stamped += $file
}

Write-Host ''
Write-Host '别忘了确认 Env.java 里这两个常量：'
foreach ($s in $stamped) { Write-Host ('  ' + $s) }
