package io.github.ffuf.pkg.filter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ffuf.pkg.ffuf.ConfigOptions;
import io.github.ffuf.pkg.ffuf.MultiError;
import org.junit.jupiter.api.Test;

class FromconfigTest {
    @Test
    void testFromConfig_DefaultStatusMatcher() {
        ConfigOptions opts = new ConfigOptions();
        MultiError errs = new MultiError();
        MatcherManager mm = MatcherManager.fromConfig(opts, true, errs);
        assertTrue(errs.isEmpty(), "FromConfig errors: " + errs.errorOrNull());
        assertTrue(mm.getMatchers().containsKey("status"), "default status matcher should be installed when addDefaultStatusMatcher=true");

        MatcherManager mm2 = MatcherManager.fromConfig(opts, false, errs);
        assertTrue(errs.isEmpty(), "FromConfig errors: " + errs.errorOrNull());
        assertFalse(mm2.getMatchers().containsKey("status"), "no status matcher should be installed when addDefaultStatusMatcher=false and none configured");
    }

    @Test
    void testFromConfig_FiltersAndMatchers() {
        ConfigOptions opts = new ConfigOptions();
        opts.filter.size = "100";
        opts.matcher.regexp = "admin";
        MultiError errs = new MultiError();
        MatcherManager mm = MatcherManager.fromConfig(opts, false, errs);
        assertTrue(errs.isEmpty(), "FromConfig errors: " + errs.errorOrNull());
        assertTrue(mm.getFilters().containsKey("size"), "size filter should be installed from opts.filter.size");
        assertTrue(mm.getMatchers().containsKey("regexp"), "regexp matcher should be installed from opts.matcher.regexp");
    }
}
