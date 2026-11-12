package io.github.ffuf.pkg.scraper;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ffuf.pkg.ffuf.MultiError;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScraperTest {
    @TempDir
    Path valid;

    /** fromDir reads the folder it is given (here an existing empty one), never a global default. */
    @Test
    void testFromDir_UsesDirnameNotGlobal() {
        MultiError errs = new MultiError();
        Scraper.fromDir(valid.toString(), "all", errs);
        assertTrue(errs.isEmpty(), "fromDir of a valid empty folder: " + errs.errorOrNull());
    }
}
