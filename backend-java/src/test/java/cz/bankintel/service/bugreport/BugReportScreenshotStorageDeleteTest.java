package cz.bankintel.service.bugreport;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Regression test for the ported {@code delete_screenshot_file} behaviour
 * (bug_report_storage.py, ř. 105): best-effort delete that never throws.
 */
class BugReportScreenshotStorageDeleteTest {

    @Test
    void deleteScreenshotFileRemovesExistingFile() throws IOException {
        BugReportScreenshotStorage storage = new BugReportScreenshotStorage();
        Path tempDir = Files.createTempDirectory("bug-report-test");
        ReflectionTestUtils.setField(storage, "storageDirOverride", tempDir.toString());
        Path file = tempDir.resolve("shot.png");
        Files.write(file, new byte[] {1, 2, 3});

        storage.deleteScreenshotFile("bug_report_uploads/shot.png");

        assertFalse(Files.exists(file));
    }

    @Test
    void deleteScreenshotFileIgnoresMissingPathWithoutThrowing() {
        BugReportScreenshotStorage storage = new BugReportScreenshotStorage();
        assertDoesNotThrow(() -> storage.deleteScreenshotFile(null));
        assertDoesNotThrow(() -> storage.deleteScreenshotFile(""));
        assertDoesNotThrow(() -> storage.deleteScreenshotFile("bug_report_uploads/does-not-exist.png"));
    }

    @Test
    void deleteScreenshotFileRejectsPathTraversal() throws IOException {
        BugReportScreenshotStorage storage = new BugReportScreenshotStorage();
        Path tempDir = Files.createTempDirectory("bug-report-test-2");
        ReflectionTestUtils.setField(storage, "storageDirOverride", tempDir.toString());
        Path outside = Files.createTempFile("outside", ".png");

        assertDoesNotThrow(() -> storage.deleteScreenshotFile("bug_report_uploads/../../" + outside.getFileName()));

        assertTrue(Files.exists(outside));
    }
}
