# iOS Color Migration Script
# 将项目内遗留的蓝绿色调（teal）转换为 iOS 系统色

$ErrorActionPreference = "Stop"

$srcRoot = "D:\Projects\DisasterGuard\frontend\src"
$targetExtensions = @(".vue", ".css")

# 颜色映射表（数组形式，避免哈希表重复键问题）
$colorReplacements = @(
    @{ Old = '#2f7f80';    New = '#007aff' },
    @{ Old = '#246b6c';    New = '#0a84ff' },
    @{ Old = '#2d8280';    New = '#007aff' },

    @{ Old = '#24494d';    New = '#1c1c1e' },
    @{ Old = '#34575a';    New = '#1c1c1e' },
    @{ Old = '#3a5c60';    New = '#1c1c1e' },
    @{ Old = '#365c60';    New = '#1c1c1e' },
    @{ Old = '#37555a';    New = '#1c1c1e' },
    @{ Old = '#275055';    New = '#1c1c1e' },
    @{ Old = '#1f3e44';    New = '#1c1c1e' },
    @{ Old = '#244b50';    New = '#1c1c1e' },
    @{ Old = '#2a4d52';    New = '#1c1c1e' },

    @{ Old = '#5f7b78';    New = 'rgba(60,60,67,.78)' },
    @{ Old = '#607c79';    New = 'rgba(60,60,67,.78)' },
    @{ Old = '#57817d';    New = 'rgba(60,60,67,.78)' },
    @{ Old = '#547474';    New = 'rgba(60,60,67,.78)' },
    @{ Old = '#5f7f7b';    New = 'rgba(60,60,67,.78)' },

    @{ Old = '#729793';    New = 'rgba(60,60,67,.52)' },
    @{ Old = '#78908f';    New = 'rgba(60,60,67,.52)' },
    @{ Old = '#829793';    New = 'rgba(60,60,67,.52)' },
    @{ Old = '#8b9f9b';    New = 'rgba(60,60,67,.52)' },
    @{ Old = '#7f9794';    New = 'rgba(60,60,67,.52)' },
    @{ Old = '#93a39e';    New = 'rgba(60,60,67,.52)' },
    @{ Old = '#9eb6b3';    New = 'rgba(60,60,67,.52)' },
    @{ Old = '#a0b2b0';    New = 'rgba(60,60,67,.52)' },
    @{ Old = '#a4b3b0';    New = 'rgba(60,60,67,.52)' },
    @{ Old = '#9eb1ae';    New = 'rgba(60,60,67,.52)' },
    @{ Old = '#b0bdb8';    New = 'rgba(60,60,67,.52)' },
    @{ Old = '#9aa9a6';    New = 'rgba(60,60,67,.52)' },
    @{ Old = '#a4aeb0';    New = 'rgba(60,60,67,.52)' },
    @{ Old = '#8fa09c';    New = 'rgba(60,60,67,.52)' },
    @{ Old = '#79aaa4';    New = 'rgba(60,60,67,.52)' },
    @{ Old = '#7ba7a2';    New = 'rgba(60,60,67,.52)' },
    @{ Old = '#789e8f';    New = 'rgba(60,60,67,.52)' },
    @{ Old = '#7c9c99';    New = 'rgba(60,60,67,.52)' },
    @{ Old = '#9aacab';    New = 'rgba(60,60,67,.52)' },

    @{ Old = '#e3ebe7';    New = 'rgba(60,60,67,.10)' },
    @{ Old = '#edf2f0';    New = 'rgba(60,60,67,.08)' },
    @{ Old = '#dbe6e2';    New = 'rgba(60,60,67,.10)' },
    @{ Old = '#d9e6e1';    New = 'rgba(60,60,67,.10)' },
    @{ Old = '#e5ece9';    New = 'rgba(60,60,67,.10)' },
    @{ Old = '#e6eeeb';    New = 'rgba(60,60,67,.10)' },
    @{ Old = '#eef3f1';    New = 'rgba(60,60,67,.08)' },
    @{ Old = '#eef2f0';    New = 'rgba(60,60,67,.08)' },
    @{ Old = '#f0f4f2';    New = 'rgba(60,60,67,.06)' },
    @{ Old = '#f1f6f4';    New = 'rgba(60,60,67,.06)' },
    @{ Old = '#f2f6f4';    New = 'rgba(60,60,67,.06)' },
    @{ Old = '#f0f7f5';    New = 'rgba(60,60,67,.05)' },
    @{ Old = '#f8fbf9';    New = 'rgba(60,60,67,.04)' },
    @{ Old = '#f8fcfb';    New = 'rgba(60,60,67,.04)' },
    @{ Old = '#fbfdfc';    New = 'rgba(60,60,67,.03)' },
    @{ Old = '#f8fbfa';    New = 'rgba(60,60,67,.04)' },
    @{ Old = '#f7faf9';    New = 'rgba(60,60,67,.04)' },
    @{ Old = '#e7f0ed';    New = 'rgba(60,60,67,.08)' },
    @{ Old = '#dfeae6';    New = 'rgba(60,60,67,.10)' },
    @{ Old = '#d8e2df';    New = 'rgba(60,60,67,.10)' },
    @{ Old = '#d5e3de';    New = 'rgba(60,60,67,.10)' },
    @{ Old = '#dce9e4';    New = 'rgba(60,60,67,.10)' },
    @{ Old = '#e0e8e5';    New = 'rgba(60,60,67,.08)' },
    @{ Old = '#e4ece8';    New = 'rgba(60,60,67,.08)' },
    @{ Old = '#e3eeec';    New = 'rgba(60,60,67,.08)' },

    @{ Old = '#e4f4ef';    New = 'rgba(0,122,255,.08)' },
    @{ Old = '#eef7f4';    New = 'rgba(0,122,255,.06)' },
    @{ Old = '#eaf3f1';    New = 'rgba(0,122,255,.06)' },
    @{ Old = '#e9f3ef';    New = 'rgba(0,122,255,.06)' },
    @{ Old = '#e9f7ef';    New = 'rgba(52,199,89,.10)' },
    @{ Old = '#e6f4ef';    New = 'rgba(52,199,89,.10)' },
    @{ Old = '#d6ece8';    New = 'rgba(52,199,89,.12)' },
    @{ Old = '#d6ece4';    New = 'rgba(52,199,89,.12)' },
    @{ Old = '#cfe5d8';    New = 'rgba(52,199,89,.10)' },
    @{ Old = '#eff9f6';    New = 'rgba(52,199,89,.08)' },

    @{ Old = '#3a8d6b';    New = '#34c759' },
    @{ Old = '#5db486';    New = '#34c759' },
    @{ Old = '#3caa82';    New = '#34c759' },
    @{ Old = '#68b58b';    New = '#34c759' },
    @{ Old = '#63d19c';    New = '#34c759' },
    @{ Old = '#56b992';    New = '#34c759' },
    @{ Old = '#228b5e';    New = '#1e7a4e' },
    @{ Old = '#5b9b76';    New = '#34c759' },
    @{ Old = '#3e8a6a';    New = '#34c759' },
    @{ Old = '#3b9a72';    New = '#34c759' }
)

$excludeDirs = @('node_modules', 'dist', '.git', 'dist.bak')

function Process-File($file) {
    $content = Get-Content -Path $file -Raw -Encoding UTF8
    $originalContent = $content

    foreach ($r in $colorReplacements) {
        $pattern = [regex]::Escape($r.Old)
        $content = [regex]::Replace($content, $pattern, $r.New, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    }

    if ($content -ne $originalContent) {
        Set-Content -Path $file -Value $content -Encoding UTF8 -NoNewline
        return $true
    }
    return $false
}

$files = @()
foreach ($ext in $targetExtensions) {
    $files += Get-ChildItem -Path $srcRoot -Recurse -Filter "*$ext" -File | Where-Object {
        $skip = $false
        foreach ($ex in $excludeDirs) {
            if ($_.FullName -like "*\$ex\*") { $skip = $true; break }
        }
        -not $skip
    }
}

Write-Host ("Found " + $files.Count + " files to process")
$updated = 0
foreach ($file in $files) {
    if (Process-File $file.FullName) { $updated++ }
}
Write-Host ("Updated " + $updated + " files")