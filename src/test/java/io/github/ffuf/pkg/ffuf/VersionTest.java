package io.github.ffuf.pkg.ffuf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class VersionTest {
    private record Case(String name, String injected, String appendix, String git, String module, String wantVersion, boolean wantReleased) {
    }

    /** selectVersion holds the whole decision, so every build path can be driven without a build made that way. */
    @Test
    void testSelectVersion() {
        List<Case> cases = List.of(
                new Case("release build reports the injected tag", "2.3.0", "", "git-20260909-256c1b02", "2.3.0", "2.3.0", true),
                new Case("source checkout reports the git identifier", "0.0.0", "-dev", "git-20260909-256c1b02", "", "git-20260909-256c1b02", false),
                new Case("vcs metadata outranks the module version so a dirty tree cannot claim a release", "0.0.0", "-dev",
                        "git-20260909-256c1b02-dirty", "2.3.0", "git-20260909-256c1b02-dirty", false),
                new Case("go install reports the module version it was built from", "0.0.0", "-dev", "", "2.2.1", "2.2.1", true),
                new Case("no metadata at all falls back to the placeholder", "0.0.0", "-dev", "", "", "0.0.0-dev", false));
        for (Case tc : cases) {
            Util.ResolvedVersion got = Util.selectVersion(tc.injected, tc.appendix, tc.git, tc.module);
            assertEquals(tc.wantVersion, got.version(), tc.name + ": version");
            assertEquals(tc.wantReleased, got.released(), tc.name + ": released");
        }
    }

    /** A git identifier is never a released version, or formattedVersion would render it as "vgit-...". */
    @Test
    void testSelectVersion_GitIdentifierIsNeverReleased() {
        for (String module : List.of("", "2.3.0", "2.3.1-0.20260909191139-256c1b024995")) {
            Util.ResolvedVersion got = Util.selectVersion("0.0.0", "-dev", "git-20260909-256c1b02", module);
            assertFalse(got.released(), "git identifier " + got.version() + " reported as released with module=" + module);
        }
    }

    /** The recorded module version is usable unless it is the "(devel)" placeholder or empty; a "v" prefix is dropped. */
    @Test
    void testNormalizeModuleVersion() {
        String[][] cases = {
            {"exact release tag", "v2.3.0", "2.3.0"},
            {"prerelease tag", "v2.3.0-rc1", "2.3.0-rc1"},
            {"pseudo version for an untagged commit", "v2.3.1-0.20260909191139-256c1b024995", "2.3.1-0.20260909191139-256c1b024995"},
            {"local build placeholder", "(devel)", ""},
            {"no version recorded", "", ""},
            {"already unprefixed", "2.3.0", "2.3.0"},
        };
        for (String[] tc : cases) {
            assertEquals(tc[2], Util.normalizeModuleVersion(tc[1]), tc[0]);
        }
    }

    /** formattedVersion adds a "v" only for released versions, never to a git identifier. */
    @Test
    void testFormattedVersion_MatchesVersion() {
        String v = Util.version();
        String formatted = Util.formattedVersion();
        assertTrue(formatted.equals(v) || formatted.equals("v" + v), "formattedVersion() = " + formatted + ", want " + v + " or v" + v);
        assertFalse(formatted.startsWith("vgit-"), "formattedVersion() = " + formatted + ", a git identifier must not be v-prefixed");
    }

    /** The version fills in the default User-Agent of every request, so it is resolved once. */
    @Test
    void testVersion_IsResolvedOnce() {
        Util.ResolvedVersion first = Util.resolveVersion();
        Util.ResolvedVersion second = Util.resolveVersion();
        assertEquals(first, second, "resolveVersion() is not stable");
    }

    /** The constant is an injection target, not a version to maintain by hand. */
    @Test
    void testVersion_ConstantIsAPlaceholder() {
        assertEquals("0.0.0", Util.VERSION, "VERSION, want the 0.0.0 placeholder; a real version here goes stale silently");
        assertNotEquals("", Util.VERSION_APPENDIX, "VERSION_APPENDIX must be non-empty in source, it is what marks a build as not-a-release");
    }

    /** The git identifier takes the first eight characters of the revision and the UTC date of the commit time. */
    @Test
    void testGitVersion() {
        Properties info = new Properties();
        assertEquals("", Util.gitVersion(info), "no metadata");
        info.setProperty("vcs.revision", "256c1b024995aaaa");
        assertEquals("", Util.gitVersion(info), "revision without a time");
        info.setProperty("vcs.time", "2026-09-09T23:30:00-02:00");
        assertEquals("git-20260910-256c1b02", Util.gitVersion(info), "time converted to UTC");
        info.setProperty("vcs.modified", "true");
        assertEquals("git-20260910-256c1b02-dirty", Util.gitVersion(info), "modified tree");
        info.setProperty("vcs.time", "not a time");
        assertEquals("", Util.gitVersion(info), "unparsable time");
    }

    /** This build carries the metadata of the commit it was made from, so it reports the version string of that commit. */
    @Test
    void testVersion_ThisBuild() {
        assertEquals("git-20260926-8d65e037", Util.version(), "version of the build from commit 8d65e037");
        assertEquals("git-20260926-8d65e037", Util.formattedVersion(), "formatted version");
    }
}
