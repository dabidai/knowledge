# Build Docker images in WSL and export to tar
# Usage: run this script from anywhere in PowerShell

$ErrorActionPreference = "Stop"

# Resolve project root (parent of docker/)
$DOCKER_DIR = $PSScriptRoot
$PROJECT_ROOT = Split-Path $DOCKER_DIR -Parent

# Convert Windows path to WSL path
# F:\code\knowledge -> /mnt/f/code/knowledge
$drive = $PROJECT_ROOT.Substring(0, 1).ToLower()
$WSL_ROOT = ("/mnt/$drive" + $PROJECT_ROOT.Substring(2)) -replace '\\', '/'

Write-Host "Project: $PROJECT_ROOT" -ForegroundColor Cyan
Write-Host "WSL path: $WSL_ROOT" -ForegroundColor Cyan

# Step 1: Build backend JAR on Windows
Write-Host "`n[1/3] Building backend JAR..." -ForegroundColor Yellow
Set-Location (Join-Path $PROJECT_ROOT "backend")
mvn package -DskipTests
if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }
Set-Location $DOCKER_DIR

# Step 2: Build Docker images in WSL
Write-Host "`n[2/3] Building Docker images in WSL..." -ForegroundColor Yellow
wsl bash -c "cd $WSL_ROOT/docker && docker compose build"
if ($LASTEXITCODE -ne 0) { throw "Docker compose build failed" }

# Step 3: Export images to tar in WSL
Write-Host "`n[3/3] Exporting images..." -ForegroundColor Yellow
wsl bash -c "cd $WSL_ROOT/docker && docker save -o kb-images.tar kb-es:8.15.0-ik kb-ai:latest kb-backend:latest kb-frontend:latest && ls -lh kb-images.tar"
if ($LASTEXITCODE -ne 0) { throw "Docker save failed" }

Write-Host "`nDone!" -ForegroundColor Green
Write-Host "File: $DOCKER_DIR\kb-images.tar"
Write-Host ""
Write-Host "Next steps on Linux server:"
Write-Host "  docker load -i kb-images.tar"
Write-Host "  docker compose up -d"
