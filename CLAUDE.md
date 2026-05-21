# trading-bot

Long-running Java/Discord bot. Listens for `!analyze`, `!pick`, `!watch`, `!play`, `!positions` and runs scheduled overnight analysis at `ANALYSIS_SCHEDULE_TIME` (default 23:00 ET).

## Run locally

```bash
mvn package -q && java -jar target/trading-bot-1.0-SNAPSHOT.jar
```

Or the saved command: `/runtrading`.

## Secrets

`.env` lives at the repo root and is gitignored. NEVER commit it. Required keys: `DISCORD_BOT_TOKEN`, `ALPACA_API_KEY`, `ALPACA_API_SECRET`, `OPENAI_API_KEY`, `ALPHA_VANTAGE_API_KEY`, `OPENROUTER_API_KEY`. Optional: `ALPACA_MODE` (default `paper`), `DISCORD_CHANNEL_NAME` (blank = all channels), `FUNDAMENTAL_LLM_MODEL`, `ANALYSIS_SCHEDULE_TIME`, `DEFAULT_QTY`.

`dotenv-java` rejects duplicate keys — keep each variable defined exactly once.

## Deploy Configuration (configured by /setup-deploy)

- Platform: container-ready, not yet deployed
- Production URL: n/a (bot is a Discord WebSocket client, no inbound URL)
- Deploy workflow: none yet
- Deploy status command: `docker ps --filter name=trading-bot` (once running)
- Merge method: squash
- Project type: long-running JVM worker (Discord bot + scheduler)
- Post-deploy health check: tail container logs for `Bot online in #...`

### Run as container

Install Docker Desktop or `colima` first: `brew install --cask docker` or `brew install colima docker && colima start`.

```bash
docker build -t trading-bot .
docker run --rm --env-file .env --name trading-bot trading-bot
```

For 24/7: add `-d --restart=unless-stopped` and drop `--rm`.

### Custom deploy hooks

- Pre-merge: `mvn package -q` (must succeed)
- Deploy trigger: manual `docker build && docker run` (no cloud target yet)
- Deploy status: `docker logs --tail 20 trading-bot | grep "Bot online"`
- Health check: log line `Bot online in #` indicates Discord gateway connected

### Future cloud targets (not configured)

Vercel and Cloudflare Workers are NOT viable — both are serverless and cannot hold the persistent Discord WebSocket. Viable options when ready:

- **Fly.io**: `fly launch --no-deploy && fly secrets import < .env && fly deploy` (~$5/mo)
- **Render** Background Worker: connect repo, set env vars in dashboard (~$7/mo)
- **Any VM** (Hetzner, DO, Lightsail): `docker run -d --restart=unless-stopped`
