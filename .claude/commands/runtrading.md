---
description: Build and run the trading bot
---

Run the trading bot. Execute exactly:

```bash
mvn package -q && java -jar target/trading-bot-1.0-SNAPSHOT.jar
```

Run from the project root (`/Users/daharimorgan/dev/trading-bot`) so `.env` is loaded. Use `run_in_background: true` so the bot keeps running while the user interacts with the shell.
