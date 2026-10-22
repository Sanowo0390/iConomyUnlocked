package io.github.townyadvanced.iconomy.system;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class MySqlTimeoutsTest {
	@Test void addsFiniteTimeoutsWithoutQueryString() {
		assertEquals("jdbc:mysql://localhost/db?connectTimeout=2000&socketTimeout=3000",
			MySqlTimeouts.applyDefaults("jdbc:mysql://localhost/db", 2000, 3000));
	}

	@Test void preservesFlagsAndExplicitTimeouts() {
		String url = "jdbc:mysql://localhost/db?useSSL=false&connectTimeout=7000&socketTimeout=0";
		assertEquals(url, MySqlTimeouts.applyDefaults(url, 2000, 3000));
	}

	@Test void fillsOnlyMissingTimeout() {
		assertEquals("jdbc:mysql://localhost/db?connectTimeout=9000&socketTimeout=3000",
			MySqlTimeouts.applyDefaults("jdbc:mysql://localhost/db?connectTimeout=9000", 2000, 3000));
	}

	@Test void handlesTrailingSeparatorsAndClampsInvalidDefaults() {
		assertEquals("jdbc:mysql://localhost/db?useSSL=false&connectTimeout=1&socketTimeout=1",
			MySqlTimeouts.applyDefaults("jdbc:mysql://localhost/db?useSSL=false&", 0, -2));
		assertEquals("jdbc:mysql://localhost/db?connectTimeout=2&socketTimeout=3",
			MySqlTimeouts.applyDefaults("jdbc:mysql://localhost/db?", 2, 3));
	}
}
