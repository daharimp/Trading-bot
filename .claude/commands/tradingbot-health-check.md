---
description: Verify the trading bot's always-on stack (colima service, docker daemon, container, gateway log line)
---

Run a full reboot-survival health check of the trading bot. Execute each step in order, report PASS/FAIL with the evidence, and stop at the first failure with a one-line remediation hint.

Run these checks via Bash. They are non-mutating reads only — never `docker rm`, `restart`, or `colima start` in this command.

```bash
eval "$(/opt/homebrew/bin/brew shellenv)"

# 1. launchd service registered (reboot-survival)
brew services list | awk '$1=="colima" && $2=="started" {found=1} END {exit !found}' \
  && echo "PASS: colima registered with launchd, state=started (auto-starts on login)" \
  || echo "FAIL: colima not started under launchd — run 'brew services restart colima'"

# 2. colima VM running (docker daemon host)
colima status 2>&1 | grep -q "colima is running" \
  && echo "PASS: colima VM running" \
  || echo "FAIL: colima VM not running — run 'colima start'"

# 3. docker daemon reachable
docker info --format '{{.ServerVersion}}' >/dev/null 2>&1 \
  && echo "PASS: docker daemon reachable ($(docker info --format '{{.ServerVersion}} ({{.OperatingSystem}}, {{.Architecture}})'))" \
  || echo "FAIL: docker daemon unreachable"

# 4. container exists, running, with restart policy
docker inspect trading-bot --format '{{.State.Status}} restart={{.HostConfig.RestartPolicy.Name}} restarts={{.RestartCount}} started={{.State.StartedAt}}' 2>/dev/null \
  | awk '/^running restart=unless-stopped/ {print "PASS: container "$0; exit 0} {print "FAIL: container state="$0" — expected running + restart=unless-stopped"; exit 1}' \
  || echo "FAIL: container 'trading-bot' missing — rebuild and run with --restart=unless-stopped"

# 5. Discord gateway online (the canonical health line from CLAUDE.md)
docker logs --tail 500 trading-bot 2>&1 | grep -q "Bot online in #" \
  && echo "PASS: gateway online ($(docker logs --tail 500 trading-bot 2>&1 | grep 'Bot online in #' | tail -1))" \
  || echo "FAIL: 'Bot online in #' not in last 500 log lines — bot may be reconnecting or token invalid"

# 6. recent heartbeat ACK (proves gateway is currently healthy, not just historically)
docker logs --since 5m trading-bot 2>&1 | grep -q "Heartbeat acknowledged" \
  && echo "PASS: heartbeat ACK in last 5min (gateway alive)" \
  || echo "FAIL: no heartbeat ACK in last 5min — gateway may be stalled"
```

After running, give the user a one-line summary:
- All PASS → "Bot is healthy and will survive reboot."
- Any FAIL → name the first failing step and the suggested fix.
