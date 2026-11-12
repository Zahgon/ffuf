package io.github.ffuf.pkg.ffuf;

import java.util.ArrayList;
import java.util.List;

/** Collects several errors into one combined report. */
public class MultiError {
    private final List<String> errors = new ArrayList<>();

    /** Creates an empty error list. */
    public MultiError() {
        // Fields start at their declared defaults.
    }

    /** Adds one error message. */
    public void add(String err) {
        errors.add(err);
    }

    /** {@return true when no error was added} */
    public boolean isEmpty() {
        return errors.isEmpty();
    }

    /** {@return the combined report of every error, or null when there is none} */
    public String errorOrNull() {
        if (errors.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(errors.size()).append(" errors occured.\n");
        for (String e : errors) {
            sb.append("\t* ").append(e).append('\n');
        }
        return sb.toString();
    }
}
