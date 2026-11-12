package io.github.ffuf.pkg.output;

import io.github.ffuf.pkg.ffuf.GoString;
import java.io.OutputStream;
import java.io.PrintStream;

/**
 * A print stream that writes strings with {@link GoString#encode}, so text that came from invalid UTF-8 input reaches
 * the terminal or file as the same raw bytes Go writes, instead of replacement characters.
 */
public class GoPrintStream extends PrintStream {
    /** Creates an auto-flushing stream over out. */
    public GoPrintStream(OutputStream out) {
        super(out, true);
    }

    @Override
    public void print(String s) {
        byte[] b = GoString.encode(String.valueOf(s));
        synchronized (this) {
            write(b, 0, b.length);
            flush();
        }
    }

    @Override
    public void print(char c) {
        print(String.valueOf(c));
    }

    @Override
    public void print(Object obj) {
        print(String.valueOf(obj));
    }

    @Override
    public void println() {
        print("\n");
    }

    @Override
    public void println(String x) {
        print(x + "\n");
    }

    @Override
    public void println(Object x) {
        print(x + "\n");
    }
}
