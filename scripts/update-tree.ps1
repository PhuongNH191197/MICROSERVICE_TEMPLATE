# update-tree.ps1
# Regenerates the raw tree section in PROJECT_TREE.md
# Run manually: .\scripts\update-tree.ps1
# Triggered automatically by Claude hook when a new Dockerfile or pom.xml is written.

param(
    [string]$Root = (Split-Path $PSScriptRoot -Parent)
)

$exclude = @("target", ".git", "__pycache__", ".pytest_cache", "models", ".mvn", "node_modules")

function Get-Tree {
    param([string]$Path, [string]$Indent = "")
    $items = Get-ChildItem -Path $Path -Force |
        Where-Object { $_.Name -notin $exclude } |
        Sort-Object { [int](-not $_.PSIsContainer) }, Name

    foreach ($item in $items) {
        if ($item.PSIsContainer) {
            "$Indent+-- $($item.Name)/"
            Get-Tree -Path $item.FullName -Indent "$Indent|   "
        } else {
            "$Indent|-- $($item.Name)"
        }
    }
}

$treeLines = Get-Tree -Path $Root
$treeContent = $treeLines -join "`n"
$today = Get-Date -Format "yyyy-MM-dd"

# Count services
$javaPoms = Get-ChildItem "$Root\infra-services", "$Root\business-services", "$Root\infrastructure", "$Root\common" -Filter "pom.xml" -Recurse -ErrorAction SilentlyContinue
$javaModules = $javaPoms.Count
$pythonServices = @(Get-ChildItem "$Root\business-services" -Directory -ErrorAction SilentlyContinue | Where-Object { Test-Path "$($_.FullName)\requirements.txt" }).Count
$total = $javaModules + $pythonServices

$newContent = @"
# Project Tree

> Auto-generated. Run ``scripts/update-tree.ps1`` to refresh.
> Last updated: $today

``````
$treeContent
``````

## Service Port Reference

| Service | Port | Language | Role |
|---------|------|----------|------|
| API Gateway | 8080 | Java | Entry point, JWT, rate limit |
| Auth Service | 8081 | Java | Register, login, JWT lifecycle |
| Notification Service | 8082 | Java | Email via RabbitMQ |
| File Service | 8083 | Java | Upload/serve via MinIO |
| User Profile Service | 8091 | Java | Example business service |
| vocal-detector | 8765 | Python | AI vocal detection (Essentia) |
| Eureka Server | 8761 | Java | Service registry |
| Config Server | 8888 | Java | Centralised config |
| PostgreSQL | 5432 | — | Main database (4 DBs) |
| Redis | 6379 | — | Rate limiting cache |
| RabbitMQ | 5672/15672 | — | Async messaging |
| MinIO | 9000/9001 | — | Object storage |
| Zipkin | 9411 | — | Distributed tracing |

## Thêm service mới

Khi thêm service mới:
1. Thêm row vào bảng Port Reference bên trên
2. Chạy ``.\scripts\update-tree.ps1`` — tree raw tự refresh

---

*Tổng hiện tại: $javaModules Java modules + $pythonServices Python service = $total services*
"@

Set-Content -Path "$Root\PROJECT_TREE.md" -Value $newContent -Encoding utf8
Write-Host "PROJECT_TREE.md updated ($today) — $total services total"
