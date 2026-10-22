package io.github.townyadvanced.iconomy.system;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class AccountReadQueriesTest {
	private static Supplier<Connection> connections(String url, AtomicInteger closed) {
		return () -> {
			try {
				Connection connection = DriverManager.getConnection(url);
				return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] { Connection.class },
					(proxy, method, args) -> {
						if (method.getName().equals("close")) closed.incrementAndGet();
						try { return method.invoke(connection, args); }
						catch (InvocationTargetException error) { throw error.getCause(); }
					});
			} catch (SQLException error) { throw new IllegalStateException(error); }
		};
	}

	@Test void preservesBedrockAndNullHiddenRowsWhileAggregatingAllAccounts() throws Exception {
		String url = "jdbc:h2:mem:" + UUID.randomUUID();
		AtomicInteger closed = new AtomicInteger();
		try (Connection anchor = DriverManager.getConnection(url); Statement statement = anchor.createStatement()) {
			statement.execute("CREATE TABLE accounts(username VARCHAR(32), balance DECIMAL(64,2), hidden BOOLEAN)");
			statement.execute("INSERT INTO accounts VALUES ('.bedrock',100.25,FALSE),('migrated',50,NULL),('hidden',900,TRUE)");
			AccountReadQueries queries = new AccountReadQueries(connections(url, closed), "accounts");
			assertEquals(List.of(".bedrock", "migrated"), List.copyOf(queries.ranking(5).keySet()));
			assertEquals(100.25, queries.ranking(1).get(".bedrock"));
			AccountReadQueries.Stats stats = queries.stats();
			assertEquals(3, stats.accounts());
			assertEquals(1050.25, stats.total());
			assertEquals(1050.25 / 3, stats.average());
			assertEquals(3, closed.get());
		}
	}

	@Test void emptyDatabaseHasZeroStatistics() throws Exception {
		String url = "jdbc:h2:mem:" + UUID.randomUUID();
		try (Connection anchor = DriverManager.getConnection(url); Statement statement = anchor.createStatement()) {
			statement.execute("CREATE TABLE accounts(username VARCHAR(32), balance DECIMAL(64,2), hidden BOOLEAN)");
			AccountReadQueries queries = new AccountReadQueries(connections(url, new AtomicInteger()), "accounts");
			assertEquals(new AccountReadQueries.Stats(0, 0), queries.stats());
			assertEquals(0, queries.stats().average());
			assertTrue(queries.ranking(5).isEmpty());
		}
	}

	@Test void sqlFailureIsNotReportedAsEmptyAndConnectionIsClosed() throws Exception {
		String url = "jdbc:h2:mem:" + UUID.randomUUID();
		AtomicInteger closed = new AtomicInteger();
		try (Connection anchor = DriverManager.getConnection(url)) {
			AccountReadQueries queries = new AccountReadQueries(connections(url, closed), "missing_table");
			assertThrows(SQLException.class, () -> queries.ranking(5));
			assertThrows(SQLException.class, queries::stats);
			assertEquals(2, closed.get());
		}
	}

	@Test void poolExhaustionBecomesAnExplicitFailure() {
		AccountReadQueries queries = new AccountReadQueries(() -> null, "accounts");
		assertThrows(SQLException.class, queries::stats);
		assertThrows(SQLException.class, () -> queries.ranking(5));
	}
}
