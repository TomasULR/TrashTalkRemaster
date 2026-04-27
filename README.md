# TrashTalk

Self-hosted Discord clone — desktop (Java Swing) + Android (Kotlin) + iOS (Swift), backend Java/Spring Boot. Hlasové/video hovory, screen sharing, chat, soubory.

Plán implementace je v `~/.claude/plans/chci-vytvo-it-discord-clone-swirling-parrot.md`.

## Repo layout

```
.
├── backend/             # Spring Boot 3 + JPA + Flyway + WebSocket
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/
├── nginx/               # Reverse proxy (TLS termination na 25565)
│   └── nginx.conf
├── scripts/             # Helper skripty (dev secrets atd.)
├── src/                 # Swing desktop klient (existující kostra)
├── docker-compose.yml   # nginx + app + postgres + minio
└── .env.example
```

## Fáze 0 — local bring-up

Předpoklady: Docker Desktop, openssl (Git for Windows ho má), volný port `25565`.

```powershell
# 1) Vygenerovat dev secrets (TLS cert + JWT keypair)
.\scripts\generate-dev-secrets.ps1

# 2) Spustit celý stack
docker compose up -d --build

# 3) Ověřit health (self-signed cert → -k pro curl)
curl -k https://localhost:25565/api/health
```

Očekávaný výstup:

```json
{"status":"ok","service":"trashtalk-backend","version":"0.1.0-SNAPSHOT","timestamp":"2026-04-26T..."}
```

## Architektura — single-port routing

Vše jde přes jediný TCP port **25565** (TLS):

| Path | Backend |
|---|---|
| `https://host:25565/api/*` | Spring Boot REST |
| `wss://host:25565/ws/signal` | WebSocket signaling |
| `wss://host:25565/ws/media/{sessionId}` | Media bridge |
| `https://host:25565/files/*` | File upload/download |

PostgreSQL a MinIO běží jen v interní Docker síti, neexponují se ven.

## Roadmap

- **Fáze 0 ✅** — Maven, Docker Compose, schema, nginx (tato fáze)
- **Fáze 1** — Auth (register/login/JWT/Argon2) + Swing AuthPanel napojený
- **Fáze 2** — Servery + kanály + role + Swing UI
- **Fáze 3** — Text chat (WebSocket) + persistence
- **Fáze 4** — Soubory (1 GB / server quota)
- **Fáze 5–7** — Voice, video, screen share
- **Fáze 8** — Settings dialog (audio/video devices, bitrate…)
- **Fáze 9–10** — Android + iOS klienti

## Bezpečnost (baseline)

Argon2id pro hesla, JWT (RS256), TLS všude. Žádné E2EE/MLS v MVP — viz [decisions_crypto.md](C:\Users\tomas\.claude\projects\c--Users-tomas-IdeaProjects-TrashTalkRemaster\memory\decisions_crypto.md).

V produkci nahraď self-signed cert v `nginx/certs/` skutečným certem (Let's Encrypt) a změň hesla v `.env`.
