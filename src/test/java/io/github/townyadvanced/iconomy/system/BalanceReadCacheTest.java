package io.github.townyadvanced.iconomy.system;

import org.junit.jupiter.api.Test;

class BalanceReadCacheTest {
    @Test void repeatedReadsAndExpiry() throws Exception { BalanceReadCacheChecks.repeatedReadsAndExpiry(); }
    @Test void writesAndOverlappingWriters() throws Exception { BalanceReadCacheChecks.writesAndOverlappingWriters(); }
    @Test void failuresMissingRowsAndDisabledCache() throws Exception { BalanceReadCacheChecks.failuresMissingRowsAndDisabledCache(); }
    @Test void inFlightReadCannotRestoreInvalidatedValue() throws Exception { BalanceReadCacheChecks.inFlightReadCannotRestoreInvalidatedValue(); }
    @Test void capacityIsBoundedAndUnavailableConnectionFails() throws Exception { BalanceReadCacheChecks.capacityIsBoundedAndUnavailableConnectionFails(); }
}
