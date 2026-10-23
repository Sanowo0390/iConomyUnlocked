package io.github.townyadvanced.iconomy.system;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Short-lived Vault read snapshots only. Transaction holdings always read the DB.
 * This is local to one plugin instance; external writers become visible at expiry.
 */
public final class BalanceReadCache {
    private final Supplier<Connection> connections;
    private final String table;
    private final long ttlNanos;
    private final LongSupplier clock;
    private final LinkedHashMap<Object, Entry> entries = new LinkedHashMap<>();
    private long generation;
    private int writers;
    private static final int MAX_ENTRIES = 4096;

    public BalanceReadCache(Supplier<Connection> connections, String table, int ttlMillis) {
        this(connections, table, ttlMillis, System::nanoTime);
    }

    BalanceReadCache(Supplier<Connection> connections, String table, int ttlMillis, LongSupplier clock) {
        this.connections = connections;
        this.table = table;
        this.ttlNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0, ttlMillis));
        this.clock = clock;
    }

    /** Null means missing; missing rows and failed queries are never cached. */
    public Double read(Object key) throws SQLException {
        if (!(key instanceof UUID) && !(key instanceof String))
            throw new IllegalArgumentException("Account key must be a UUID or name");
        long version;
        synchronized (this) {
            Entry entry = entries.get(key);
            if (writers == 0 && entry != null && clock.getAsLong() - entry.created() < ttlNanos)
                return entry.balance();
            entries.remove(key);
            version = generation;
        }

        Double balance = query(key);
        synchronized (this) {
            // An in-flight read must not repopulate a snapshot invalidated by a write.
            if (balance != null && ttlNanos > 0 && writers == 0 && version == generation) {
                if (entries.size() >= MAX_ENTRIES)
                    entries.remove(entries.keySet().iterator().next());
                entries.put(key, new Entry(balance, clock.getAsLong()));
            }
        }
        return balance;
    }

    private Double query(Object key) throws SQLException {
        Connection connection = connections.get();
        if (connection == null)
            throw new SQLException("No database connection available");
        try (connection; PreparedStatement statement = connection.prepareStatement(
                "SELECT balance FROM " + table + " WHERE " + (key instanceof UUID ? "uuid" : "username") + " = ? LIMIT 1")) {
            statement.setString(1, key.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getDouble("balance") : null;
            }
        }
    }

    /** Pair with endWrite in finally, even on SQL failure. */
    public synchronized void beginWrite() {
        writers++;
        generation++;
        entries.clear();
    }

    public synchronized void endWrite() {
        writers--;
        generation++;
        entries.clear();
    }

    private record Entry(double balance, long created) {}
}
