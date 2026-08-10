package cz.bankintel.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BankIntelSchedulerLeaderTest {

    @AfterEach
    void cleanup() {
        System.clearProperty("BANKINTEL_SCHEDULER_LEADER");
    }

    @Test
    void leaderFalseByDefault() {
        assertFalse(BankIntelScheduler.isSchedulerLeader());
    }

    @Test
    void leaderTrueWhenEnvSet() {
        System.setProperty("BANKINTEL_SCHEDULER_LEADER", "1");
        assertTrue(BankIntelScheduler.isSchedulerLeader());
    }
}
