package io.github.ffuf.pkg.ffuf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Checks GoStrconv against strconv.ParseInt(s, 0, 64) and strconv.ParseBool as Go 1.27 computes them. */
class GoStrconvTest {
    @ParameterizedTest
    @CsvSource({
        "1_000, 1000", "1_0, 10", "0x1F, 31", "0X_1f, 31", "0o17, 15", "017, 15", "0b101, 5", "-0x10, -16", "+12, 12",
        "0_7, 7", "9223372036854775807, 9223372036854775807", "-9223372036854775808, -9223372036854775808",
    })
    void parsesValidIntegers(String in, long want) {
        GoStrconv.IntResult r = GoStrconv.parseInt(in, 0);
        assertNull(r.error());
        assertEquals(want, r.value());
    }

    @ParameterizedTest
    @CsvSource(value = {"08", "0b", "0x", "1__0", "_1", "1_", "\uff10", "''", "-", "0x_", "1e3"}, emptyValue = "")
    void rejectsSyntax(String in) {
        assertEquals(GoStrconv.Error.SYNTAX, GoStrconv.parseInt(in, 0).error());
    }

    @ParameterizedTest
    @CsvSource({
        "9223372036854775808, 9223372036854775807", "-9223372036854775809, -9223372036854775808",
        "99999999999999999999x, 9223372036854775807",
    })
    void reportsRangeBeforeLaterSyntax(String in, long clamped) {
        GoStrconv.IntResult r = GoStrconv.parseInt(in, 0);
        assertEquals(GoStrconv.Error.RANGE, r.error());
        assertEquals(clamped, r.value());
    }

    @ParameterizedTest
    @CsvSource({"1, true", "t, true", "TRUE, true", "True, true", "0, false", "F, false", "False, false"})
    void parsesBool(String in, boolean want) {
        assertEquals(want, GoStrconv.parseBool(in));
    }

    @ParameterizedTest
    @CsvSource({"yes", "tRUE", "on", "2"})
    void rejectsBool(String in) {
        assertNull(GoStrconv.parseBool(in));
    }
}
