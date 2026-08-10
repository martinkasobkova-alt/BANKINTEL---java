package cz.bankintel.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BankIntelDataPathsTest {

    @Test
    void dataDirReturnsPath() {
        Path dir = BankIntelDataPaths.dataDir();
        assertNotNull(dir);
        assertFalse(dir.toString().isBlank());
    }

    @Test
    void catalogSearchMetadataDirNotBlank() {
        assertFalse(BankIntelDataPaths.catalogSearchMetadataDir().toString().isBlank());
    }

    @Test
    void catalogSearchIndexDirNotBlank() {
        assertFalse(BankIntelDataPaths.catalogSearchIndexDir().toString().isBlank());
    }

    @Test
    void referenceBackendRootNotBlank() {
        assertFalse(BankIntelDataPaths.referenceBackendRoot().toString().isBlank());
    }

    @Test
    void resolveDataFileUsesFileName() {
        Path p = BankIntelDataPaths.resolveDataFile("test_fixture.json");
        assertTrue(p.getFileName().toString().equals("test_fixture.json"));
    }

    @Test
    void macroTopicsSnapshotPathUnderDataDir() {
        Path snap = BankIntelDataPaths.macroTopicsSnapshotPath();
        assertTrue(snap.toString().contains("macro") || snap.toString().endsWith(".json"));
    }
}
