package io.github.townyadvanced.iconomy.system;

import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Runs directly with a JDK, and through the JUnit wrapper in Maven. */
public final class BalanceReadCacheChecks {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0009-01ff04d028c6");

    static final class Database {
        final AtomicInteger queries = new AtomicInteger(), closed = new AtomicInteger();
        final AtomicLong time = new AtomicLong();
        volatile Double balance = 100.0;
        volatile boolean fail;
        volatile Runnable beforeResult = () -> {};

        Connection connect() {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (p, m, args) -> switch (m.getName()) {
                    case "prepareStatement" -> {
                        String sql = (String) args[0];
                        check(sql.startsWith("SELECT balance FROM accounts WHERE "), "narrow projection");
                        yield Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                            new Class<?>[] {PreparedStatement.class}, (sp, sm, sa) -> {
                                if (sm.getName().equals("executeQuery")) {
                                    queries.incrementAndGet();
                                    if (fail) throw new SQLException("offline");
                                    Double snapshot = balance;
                                    beforeResult.run();
                                    AtomicBoolean visited = new AtomicBoolean();
                                    return Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                                        new Class<?>[] {ResultSet.class}, (rp, rm, ra) -> switch (rm.getName()) {
                                            case "next" -> !visited.getAndSet(true) && snapshot != null;
                                            case "getDouble" -> snapshot;
                                            case "close" -> null;
                                            default -> throw new UnsupportedOperationException(rm.getName());
                                        });
                                }
                                if (sm.getName().equals("setString") || sm.getName().equals("close")) return null;
                                throw new UnsupportedOperationException(sm.getName());
                            });
                    }
                    case "close" -> { closed.incrementAndGet(); yield null; }
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        }

        BalanceReadCache cache(int ttl) {
            return new BalanceReadCache(this::connect, "accounts", ttl, time::get);
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    static void repeatedReadsAndExpiry() throws Exception {
        Database db = new Database();
        BalanceReadCache cache = db.cache(250);
        for (int i = 0; i < 1000; i++) check(cache.read(ID) == 100.0, "balance");
        check(db.queries.get() == 1, "1000 repeated UUID reads must use one SQL query");
        check(cache.read(".bedrock") == 100.0, "Bedrock name");
        check(cache.read(".bedrock") == 100.0, "cached name");
        check(db.queries.get() == 2, "names cached independently");
        db.balance = 80.0;
        db.time.set(TimeUnit.MILLISECONDS.toNanos(250));
        check(cache.read(ID) == 80.0, "external change visible exactly at expiry");
        check(db.closed.get() == 3, "all SQL connections closed");
    }

    static void writesAndOverlappingWriters() throws Exception {
        Database db = new Database();
        BalanceReadCache cache = db.cache(250);
        cache.read(ID);
        cache.read(".bedrock");
        cache.beginWrite();
        db.balance = 90.0;
        check(cache.read(ID) == 90.0, "writes bypass old snapshots");
        cache.beginWrite();
        db.balance = 70.0;
        cache.endWrite();
        cache.read(ID);
        cache.read(ID);
        check(db.queries.get() == 5, "remaining writer prevents caching");
        cache.endWrite();
        check(cache.read(".bedrock") == 70.0, "name snapshot invalidated too");
        check(cache.read(ID) == 70.0, "new committed value");
        cache.read(ID);
        check(db.queries.get() == 7, "reads cache after all writers end");
    }

    static void failuresMissingRowsAndDisabledCache() throws Exception {
        Database db = new Database();
        BalanceReadCache cache = db.cache(250);
        db.fail = true;
        try { cache.read(ID); throw new AssertionError("SQL error swallowed"); }
        catch (SQLException expected) {}
        db.fail = false;
        check(cache.read(ID) == 100.0, "SQL failure not cached");
        check(db.closed.get() == 2, "failed query closes connection");
        cache.beginWrite();
        db.balance = null;
        cache.endWrite();
        check(cache.read(ID) == null, "missing account");
        db.balance = 55.0;
        check(cache.read(ID) == 55.0, "missing account not cached");
        BalanceReadCache disabled = db.cache(0);
        int count = db.queries.get();
        disabled.read(ID);
        disabled.read(ID);
        check(db.queries.get() == count + 2, "zero disables cache");
    }

    static void inFlightReadCannotRestoreInvalidatedValue() throws Exception {
        Database db = new Database();
        BalanceReadCache cache = db.cache(250);
        CountDownLatch read = new CountDownLatch(1), finish = new CountDownLatch(1);
        db.beforeResult = () -> {
            read.countDown();
            try { check(finish.await(3, TimeUnit.SECONDS), "read release timeout"); }
            catch (InterruptedException e) { throw new RuntimeException(e); }
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Double> old = executor.submit(() -> cache.read(ID));
            check(read.await(3, TimeUnit.SECONDS), "read start timeout");
            cache.beginWrite();
            db.balance = 25.0;
            cache.endWrite();
            db.beforeResult = () -> {};
            finish.countDown();
            check(old.get(3, TimeUnit.SECONDS) == 100.0, "in-flight SQL snapshot");
            check(cache.read(ID) == 25.0, "in-flight read must not restore invalidated value");
            check(db.queries.get() == 2, "new query after concurrent write");
        } finally {
            finish.countDown();
            executor.shutdownNow();
        }
    }

    static void capacityIsBoundedAndUnavailableConnectionFails() throws Exception {
        Database db = new Database();
        BalanceReadCache cache = db.cache(250);
        cache.read(ID);
        for (int i = 0; i < 4096; i++) cache.read("name" + i);
        int count = db.queries.get();
        cache.read(ID);
        check(db.queries.get() == count + 1, "old entry evicted at capacity");
        BalanceReadCache unavailable = new BalanceReadCache(() -> null, "accounts", 250);
        try { unavailable.read(ID); throw new AssertionError("null connection swallowed"); }
        catch (SQLException expected) {}
    }

    public static void main(String[] args) throws Exception {
        repeatedReadsAndExpiry();
        writesAndOverlappingWriters();
        failuresMissingRowsAndDisabledCache();
        inFlightReadCannotRestoreInvalidatedValue();
        capacityIsBoundedAndUnavailableConnectionFails();
        System.out.println("PASS: 5 balance cache regression groups; 1000 repeated reads -> 1 SQL query.");
    }
}
