# iOS Color Migration Script - Phase 2
# 进一步清理剩余的 teal/legacy 颜色

$ErrorActionPreference = "Stop"

$srcRoot = "D:\Projects\DisasterGuard\frontend\src"
$targetExtensions = @(".vue", ".css")

$colorReplacements = @(
    @{ Old = '#1f4f4a';    New = '#0a84ff' },
    @{ Old = '#379093';    New = '#0a84ff' },
    @{ Old = '#225a55';    New = '#246b6c' },
    @{ Old = '#6e7f7c';    New = 'rgba(60,60,67,.52)' },
    @{ Old = '#7b8f8c';    New = 'rgba(60,60,67,.52)' },
    @{ Old = '#7a9693';    New = 'rgba(60,60,67,.52)' },
    @{ Old = '#6d8f8a';    New = 'rgba(60,60,67,.52)' },
    @{ Old = '#7ea9a6';    New = 'rgba(60,60,67,.52)' },
    @{ Old = '#6f9290';    New = 'rgba(60,60,67,.52)' },
    @{ Old = '#8aa7a4';    New = 'rgba(60,60,67,.52)' },
    @{ Old = '#9aafac';    New = 'rgba(60,60,67,.52)' },
    @{ Old = '#7d9a97';    New = 'rgba(60,60,67,.52)' },
    @{ Old = '#99a7a1';    New = 'rgba(60,60,67,.52)' },
    @{ Old = '#a4b3b0';    New = 'rgba(60,60,67,.52)' },
    @{ Old = '#97b1ae';    New = 'rgba(60,60,67,.52)' },
    @{ Old = '#a0aeb0';    New = 'rgba(60,60,67,.52)' },
    @{ Old = '#a6b5b2';    New = 'rgba(60,60,67,.52)' },
    @{ Old = '#b8c9c5';    New = 'rgba(60,60,67,.52)' },
    @{ Old = '#b0bdb8';    New = 'rgba(60,60,67,.52)' },
    @{ Old = '#a9b6b1';    New = 'rgba(60,60,67,.52)' },
    @{ Old = '#9eb1ae';    New = 'rgba(60,60,67,.52)' },

    # 边框补充
    @{ Old = '#d8e3df';    New = 'rgba(60,60,67,.10)' },
    @{ Old = '#d8e5e0';    New = 'rgba(60,60,67,.10)' },
    @{ Old = '#e0e8e5';    New = 'rgba(60,60,67,.08)' },
    @{ Old = '#dee7e3';    New = 'rgba(60,60,67,.10)' },
    @{ Old = '#dee8e5';    New = 'rgba(60,60,67,.10)' },
    @{ Old = '#d3e8e4';    New = 'rgba(60,60,67,.08)' },

    # 渐变 + 阴影中的 teal
    @{ Old = 'rgba(47,127,128';  New = 'rgba(0,122,255' },
    @{ Old = 'rgba(47, 127, 128'; New = 'rgba(0, 122, 255' },
    @{ Old = 'rgba(108,191,165'; New = 'rgba(52,199,89' },
    @{ Old = 'rgba(108, 191, 165'; New = 'rgba(52, 199, 89' },
    @{ Old = 'rgba(30, 75, 69';  New = 'rgba(0, 0, 0' },
    @{ Old = 'rgba(31, 79, 70';  New = 'rgba(0, 0, 0' },
    @{ Old = 'rgba(31,79,70';    New = 'rgba(0,0,0' },
    @{ Old = 'rgba(82, 151, 132'; New = 'rgba(60,60,67,' },
    @{ Old = 'rgba(82,151,132';  New = 'rgba(60,60,67,' },
    @{ Old = 'rgba(37,80,73';    New = 'rgba(0,0,0' },
    @{ Old = 'rgba(91, 167, 183'; New = 'rgba(92, 200, 250' },
    @{ Old = 'rgba(91,167,183';  New = 'rgba(92,200,250' },
    @{ Old = 'rgba(106, 159, 214'; New = 'rgba(92, 200, 250' },
    @{ Old = 'rgba(106,159,214'; New = 'rgba(92,200,250' },
    @{ Old = 'rgba(78, 124, 119'; New = 'rgba(60, 60, 67' },
    @{ Old = 'rgba(78,124,119';  New = 'rgba(60,60,67' },
    @{ Old = 'rgba(18, 58, 62';  New = 'rgba(0, 0, 0' },
    @{ Old = 'rgba(18,58,62';    New = 'rgba(0,0,0' },
    @{ Old = 'rgba(25, 56, 55';  New = 'rgba(0, 0, 0' },
    @{ Old = 'rgba(25,56,55';    New = 'rgba(0,0,0' },
    @{ Old = 'rgba(25, 65, 64';  New = 'rgba(0, 0, 0' },
    @{ Old = 'rgba(25,65,64';    New = 'rgba(0,0,0' },
    @{ Old = 'rgba(31, 62, 68';  New = 'rgba(0, 0, 0' },
    @{ Old = 'rgba(31,62,68';    New = 'rgba(0,0,0' },
    @{ Old = 'rgba(57, 88, 82';  New = 'rgba(0, 0, 0' },
    @{ Old = 'rgba(57,88,82';    New = 'rgba(0,0,0' }
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