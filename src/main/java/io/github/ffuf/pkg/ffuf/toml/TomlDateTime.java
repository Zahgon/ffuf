package io.github.ffuf.pkg.ffuf.toml;

/**
 * The TOML date and time values go-toml produces: local dates, local times, local date-times and offset date-times
 * (a Go {@code time.Time} in UTC or in a fixed unnamed zone). Each renders the way Go prints it.
 */
public sealed interface TomlDateTime {
    /** {@return Go's {@code %v} text of the value} */
    String goString();

    /** {@return Go's {@code %#v} text of the value as an unexported struct field, where fmt ignores its methods} */
    String goSyntax();

    /** {@return the value's {@code %T} type name} */
    String goType();

    /** {@return the text go-toml's writer emits for the value} */
    String tomlString();

    /** A {@code toml.LocalDate}. */
    record Date(int year, int month, int day) implements TomlDateTime {
        @Override
        public String goString() {
            return String.format("%04d-%02d-%02d", year, month, day);
        }

        @Override
        public String goSyntax() {
            return "toml.LocalDate{Year:" + year + ", Month:" + month + ", Day:" + day + "}";
        }

        @Override
        public String goType() {
            return "toml.LocalDate";
        }

        @Override
        public String tomlString() {
            return goString();
        }
    }

    /** A {@code toml.LocalTime}. */
    record Time(int hour, int minute, int second, int nanosecond) implements TomlDateTime {
        @Override
        public String goString() {
            String s = String.format("%02d:%02d:%02d", hour, minute, second);
            return nanosecond == 0 ? s : s + String.format(".%09d", nanosecond);
        }

        @Override
        public String goSyntax() {
            return "toml.LocalTime{Hour:" + hour + ", Minute:" + minute + ", Second:" + second + ", Nanosecond:"
                    + nanosecond + "}";
        }

        @Override
        public String goType() {
            return "toml.LocalTime";
        }

        @Override
        public String tomlString() {
            return goString();
        }
    }

    /** A {@code toml.LocalDateTime}. */
    record DateTime(Date date, Time time) implements TomlDateTime {
        @Override
        public String goString() {
            return date.goString() + "T" + time.goString();
        }

        @Override
        public String goSyntax() {
            return "toml.LocalDateTime{Date:" + date.goSyntax() + ", Time:" + time.goSyntax() + "}";
        }

        @Override
        public String goType() {
            return "toml.LocalDateTime";
        }

        @Override
        public String tomlString() {
            return goString();
        }
    }

    /** An offset date-time with its zone offset in seconds; a zero offset prints as UTC, the way Go prints it. */
    record Offset(Date date, Time time, int offsetSeconds) implements TomlDateTime {
        private String zone(boolean colon) {
            int off = Math.abs(offsetSeconds) / 60;
            String sign = offsetSeconds < 0 ? "-" : "+";
            return String.format(colon ? "%s%02d:%02d" : "%s%02d%02d", sign, off / 60, off % 60);
        }

        @Override
        public String goString() {
            StringBuilder sb = new StringBuilder(date.goString()).append(' ')
                    .append(String.format("%02d:%02d:%02d", time.hour(), time.minute(), time.second()));
            if (time.nanosecond() != 0) {
                String frac = String.format("%09d", time.nanosecond()).replaceAll("0+$", "");
                sb.append('.').append(frac);
            }
            String numeric = zone(false);
            sb.append(' ').append(numeric).append(' ').append(offsetSeconds == 0 ? "UTC" : numeric);
            return sb.toString();
        }

        /**
         * Go's {@code %#v} of a {@code time.Time} held in an unexported field, where fmt prints the raw struct: wall
         * nanoseconds, seconds since year 1 and the location pointer (nil for UTC; a fixed zone's address is not
         * reproducible, so it is shown as nil too).
         */
        @Override
        public String goSyntax() {
            long ext = java.time.LocalDateTime.of(date.year(), date.month(), date.day(), time.hour(), time.minute(),
                    time.second()).toEpochSecond(java.time.ZoneOffset.UTC) - offsetSeconds + 62135596800L;
            return "time.Time{wall:0x" + Integer.toHexString(time.nanosecond()) + ", ext:" + ext
                    + ", loc:(*time.Location)(nil)}";
        }

        @Override
        public String goType() {
            return "time.Time";
        }

        @Override
        public String tomlString() {
            return date.goString() + "T" + String.format("%02d:%02d:%02d", time.hour(), time.minute(), time.second())
                    + (offsetSeconds == 0 ? "Z" : zone(true));
        }
    }

    /**
     * Parses a {@code YYYY-MM-DD} lexer token as Go's {@code time.Parse("2006-01-02", s)} does.
     *
     * @throws TomlException when the token is not a valid date
     */
    static Date parseDate(String s) throws TomlException {
        Date d = new Date(Integer.parseInt(s.substring(0, 4)), Integer.parseInt(s.substring(5, 7)),
                Integer.parseInt(s.substring(8, 10)));
        checkMonth(s, d);
        checkDay(s, d);
        return d;
    }

    /**
     * Parses an {@code HH:MM:SS[.fraction]} lexer token as Go's {@code time.Parse("15:04:05.999999999", s)} does.
     *
     * @throws TomlException when the token is not a valid time
     */
    static Time parseTime(String s) throws TomlException {
        return parseClock(s, s);
    }

    /**
     * Parses a local date-time joined with {@code T}, as go-toml's {@code ParseLocalDateTime} does: on failure it
     * retries with a lowercase {@code t} layout and reports that second error.
     *
     * @throws TomlException when the token is not a valid local date-time
     */
    static DateTime parseDateTime(String s) throws TomlException {
        Date d = new Date(Integer.parseInt(s.substring(0, 4)), Integer.parseInt(s.substring(5, 7)),
                Integer.parseInt(s.substring(8, 10)));
        checkMonth(s, d);
        try {
            Time t = parseClock(s, s.substring(11));
            checkDay(s, d);
            return new DateTime(d, t);
        } catch (TomlException e) {
            throw new TomlException("parsing time \"" + s + "\" as \"2006-01-02t15:04:05.999999999\": cannot parse \""
                    + s.substring(10) + "\" as \"t\"");
        }
    }

    /**
     * Parses an offset date-time joined with {@code T}, as Go's {@code time.ParseInLocation(RFC3339Nano)} does.
     *
     * @throws TomlException when the token is not a valid offset date-time
     */
    static Offset parseOffset(String s) throws TomlException {
        Date d = new Date(Integer.parseInt(s.substring(0, 4)), Integer.parseInt(s.substring(5, 7)),
                Integer.parseInt(s.substring(8, 10)));
        checkMonth(s, d);
        String rest = s.substring(11);
        int end = 8;
        if (rest.length() > 8 && rest.charAt(8) == '.') {
            end = 9;
            while (end < rest.length() && Lexer.isDigit(rest.charAt(end))) {
                end++;
            }
        }
        Time t = parseClock(s, rest.substring(0, end));
        String zone = rest.substring(end);
        int offset = 0;
        if (!zone.equals("Z")) {
            int hr = Integer.parseInt(zone.substring(1, 3));
            int mm = Integer.parseInt(zone.substring(4, 6));
            String range = null;
            if (hr > 24) {
                range = "time zone offset hour";
            }
            if (mm > 60) {
                range = "time zone offset minute";
            }
            if (range != null) {
                throw rangeError(s, range);
            }
            offset = (hr * 60 + mm) * 60 * (zone.charAt(0) == '-' ? -1 : 1);
        }
        checkDay(s, d);
        return new Offset(d, t, offset);
    }

    private static Time parseClock(String whole, String s) throws TomlException {
        int hour = Integer.parseInt(s.substring(0, 2));
        if (hour >= 24) {
            throw rangeError(whole, "hour");
        }
        int minute = Integer.parseInt(s.substring(3, 5));
        if (minute >= 60) {
            throw rangeError(whole, "minute");
        }
        int second = Integer.parseInt(s.substring(6, 8));
        if (second >= 60) {
            throw rangeError(whole, "second");
        }
        int nanos = 0;
        if (s.length() > 9) {
            String frac = s.substring(9);
            if (frac.length() > 9) {
                frac = frac.substring(0, 9);
            }
            nanos = Integer.parseInt(frac);
            for (int i = frac.length(); i < 9; i++) {
                nanos *= 10;
            }
        }
        return new Time(hour, minute, second, nanos);
    }

    private static void checkMonth(String whole, Date d) throws TomlException {
        if (d.month() <= 0 || d.month() > 12) {
            throw rangeError(whole, "month");
        }
    }

    private static void checkDay(String whole, Date d) throws TomlException {
        if (d.day() < 1 || d.day() > java.time.YearMonth.of(d.year(), d.month()).lengthOfMonth()) {
            throw rangeError(whole, "day");
        }
    }

    private static TomlException rangeError(String whole, String what) {
        return new TomlException("parsing time \"" + whole + "\": " + what + " out of range");
    }
}
