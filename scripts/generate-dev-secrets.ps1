#requires -Version 5.1
# Generuje self-signed TLS cert a RSA keypair pro JWT (dev only).
# Vyžaduje openssl v PATH (např. z Git for Windows nebo Win-OpenSSL).

$ErrorActionPreference = 'Stop'

$RootDir    = Split-Path -Parent $PSScriptRoot
$CertDir    = Join-Path $RootDir 'nginx\certs'
$SecretsDir = Join-Path $RootDir 'secrets'

New-Item -ItemType Directory -Force -Path $CertDir, $SecretsDir | Out-Null

if (-not (Get-Command openssl -ErrorAction SilentlyContinue)) {
    Write-Error "openssl nenalezen v PATH. Nainstaluj Git for Windows (obsahuje openssl) nebo Win-OpenSSL."
}

# TLS cert
$crt = Join-Path $CertDir 'server.crt'
$key = Join-Path $CertDir 'server.key'
if (-not (Test-Path $crt) -or -not (Test-Path $key)) {
    Write-Host '>> Generating self-signed TLS cert'
    & openssl req -x509 -newkey rsa:4096 -sha256 -nodes `
        -keyout $key `
        -out    $crt `
        -days   365 `
        -subj   '/C=CZ/O=TrashTalk/CN=46.23.61.86' `
        -addext 'subjectAltName=IP:46.23.61.86,IP:127.0.0.1,DNS:localhost'
} else {
    Write-Host '== TLS cert již existuje — preskakuji'
}

# JWT keypair
$priv = Join-Path $SecretsDir 'jwt_private.pem'
$pub  = Join-Path $SecretsDir 'jwt_public.pem'
if (-not (Test-Path $priv)) {
    Write-Host '>> Generating JWT RSA keypair'
    & openssl genrsa -out $priv 2048
    & openssl rsa -in $priv -pubout -out $pub
} else {
    Write-Host '== JWT keypair již existuje — preskakuji'
}

Write-Host ''
Write-Host '== Hotovo. Spusť backend: docker compose up -d'
