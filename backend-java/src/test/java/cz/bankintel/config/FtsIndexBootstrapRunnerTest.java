package cz.bankintel.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cz.bankintel.search.CatalogSearchProperties;
import cz.bankintel.search.FtsIndexBootstrapRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;

class FtsIndexBootstrapRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void skipsBootstrapWhenFtsDbAlreadyExists() throws Exception {
        Path indexDir = tempDir.resolve("indexes");
        Files.createDirectories(indexDir);
        Path ftsDb = indexDir.resolve("classic_catalog_search.sqlite");
        Files.writeString(ftsDb, "fake-sqlite");

        CatalogSearchProperties props = mock(CatalogSearchProperties.class);
        when(props.ftsDbPath()).thenReturn(ftsDb);
        when(props.indexDir()).thenReturn(indexDir);

        FtsIndexBootstrapRunner runner = new FtsIndexBootstrapRunner(props);
        runner.run(new DefaultApplicationArguments());

        verify(props, never()).indexDir();
    }

    @Test
    void skipsWhenMissingSnapshotUrl() throws Exception {
        Path indexDir = tempDir.resolve("indexes");
        Files.createDirectories(indexDir);
        Path ftsDb = indexDir.resolve("classic_catalog_search.sqlite");

        CatalogSearchProperties props = mock(CatalogSearchProperties.class);
        when(props.ftsDbPath()).thenReturn(ftsDb);
        when(props.indexDir()).thenReturn(indexDir);

        FtsIndexBootstrapRunner runner = new FtsIndexBootstrapRunner(props);
        runner.run(new DefaultApplicationArguments());

        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(ftsDb));
    }
}
