package com.tradingbot.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

public class DatabaseManager {

    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);

    private final HikariDataSource dataSource;
    private final Jdbi jdbi;

    /**
     * Initializes the connection pool and runs Flyway migrations.
     * dbPath: absolute path to the SQLite file (e.g. "/data/trading-bot.db" on Fly,
     *         or "./trading-bot.db" locally).
     */
    public DatabaseManager(String dbPath) {
        // Ensure parent directory exists (important on Fly volumes mounted at /data)
        try {
            Path parent = Path.of(dbPath).toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (Exception e) {
            log.warn("Could not create DB directory: {}", e.getMessage());
        }

        String jdbcUrl = "jdbc:sqlite:" + dbPath;

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(1);   // SQLite is single-writer; 1 connection avoids lock contention
        config.setConnectionTimeout(10_000);
        config.addDataSourceProperty("journal_mode", "WAL");
        config.addDataSourceProperty("synchronous", "NORMAL");
        config.addDataSourceProperty("foreign_keys", "true");

        dataSource = new HikariDataSource(config);

        Flyway.configure()
              .dataSource(dataSource)
              .locations("classpath:db/migration")
              .baselineOnMigrate(true)
              .load()
              .migrate();

        jdbi = Jdbi.create(dataSource);
        log.info("Database ready: {}", dbPath);
    }

    public Jdbi jdbi() {
        return jdbi;
    }

    public void close() {
        dataSource.close();
    }
}
