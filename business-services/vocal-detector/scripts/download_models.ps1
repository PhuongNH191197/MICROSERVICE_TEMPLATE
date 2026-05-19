$ErrorActionPreference = "Stop"

$dest = Join-Path $PSScriptRoot "..\models"
New-Item -ItemType Directory -Force -Path $dest | Out-Null

$base = "https://essentia.upf.edu/models"

$files = @(
    @{
        url  = "$base/feature-extractors/discogs-effnet/discogs-effnet-bs64-1.pb"
        out  = "discogs-effnet-bs64-1.pb"
        desc = "discogs-effnet embedding model"
    },
    @{
        url  = "$base/classification-heads/voice_instrumental/voice_instrumental-discogs-effnet-1.pb"
        out  = "voice_instrumental-discogs-effnet-1.pb"
        desc = "voice/instrumental classifier"
    },
    @{
        url  = "$base/classification-heads/voice_instrumental/voice_instrumental-discogs-effnet-1.json"
        out  = "voice_instrumental-discogs-effnet-1.json"
        desc = "classifier metadata"
    }
)

foreach ($f in $files) {
    $outPath = Join-Path $dest $f.out
    Write-Host "Downloading $($f.desc)..."
    Invoke-WebRequest -Uri $f.url -OutFile $outPath -UseBasicParsing
    Write-Host "  -> $outPath"
}

Write-Host ""
Write-Host "Done. Models saved to: $dest"
