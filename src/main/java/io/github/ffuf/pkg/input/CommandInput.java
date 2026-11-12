package io.github.ffuf.pkg.input;

import io.github.ffuf.pkg.ffuf.Config;
import java.io.IOException;
import java.io.InputStream;

/** Payloads produced by running a shell command once per position, with FFUF_NUM set. */
public class CommandInput implements InternalInputProvider {
    private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");
    /** The default shell and its command argument, per platform. */
    static final String SHELL_CMD = WINDOWS ? "cmd.exe" : "/bin/sh";
    static final String SHELL_ARG = WINDOWS ? "/C" : "-c";
    private final Config config;
    private final String keyword;
    private final String command;
    private final String shell;
    private int count;
    private boolean active = true;

    /**
     * Creates the provider that runs command for each payload.
     *
     * @param keyword the keyword the payloads replace
     * @param command the shell command; FFUF_NUM holds the payload position
     * @param conf the configuration, for -input-num and -input-shell
     */
    public CommandInput(String keyword, String command, Config conf) {
        this.keyword = keyword;
        this.command = command;
        this.config = conf;
        this.shell = conf.inputShell.isEmpty() ? SHELL_CMD : conf.inputShell;
    }

    @Override
    public String keyword() {
        return keyword;
    }

    @Override
    public boolean next() {
        return count < config.inputNum;
    }

    @Override
    public void incrementPosition() {
        count++;
    }

    /**
     * Runs the command through the shell with FFUF_NUM set to the current position. The child process is killed if
     * reading its output fails or the wait is interrupted (the interrupt flag is restored).
     *
     * @return the command's standard output, or an empty array when it cannot start, fails or exits non-zero
     */
    @Override
    public byte[] value() {
        ProcessBuilder pb = new ProcessBuilder(shell, SHELL_ARG, command);
        pb.environment().put("FFUF_NUM", Integer.toString(count));
        pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            return new byte[0];
        }
        boolean exited = false;
        try {
            byte[] out;
            try (InputStream in = p.getInputStream()) {
                out = in.readAllBytes();
            }
            int status = p.waitFor();
            exited = true;
            return status == 0 ? out : new byte[0];
        } catch (IOException e) {
            return new byte[0];
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new byte[0];
        } finally {
            if (!exited) {
                p.destroyForcibly();
            }
        }
    }

    @Override
    public long total() {
        return config.inputNum;
    }

    @Override
    public void resetPosition() {
        count = 0;
    }

    @Override
    public void setPosition(int pos) {
        count = pos;
    }

    @Override
    public boolean active() {
        return active;
    }

    @Override
    public void disable() {
        active = false;
    }
}
