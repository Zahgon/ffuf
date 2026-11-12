package io.github.ffuf.pkg.input;

import io.github.ffuf.pkg.ffuf.Config;
import io.github.ffuf.pkg.ffuf.InputProviderConfig;
import io.github.ffuf.pkg.ffuf.MultiError;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Combines the keyword providers in clusterbomb, pitchfork or sniper order. */
public class MainInputProvider {
    private final List<InternalInputProvider> providers = new ArrayList<>();
    private final Map<String, Encoders> encoders = new LinkedHashMap<>();
    private final Config config;
    private int position;
    private int msbIterator;

    /** Creates a provider for each configured wordlist or command, adding problems to errs. */
    public MainInputProvider(Config conf, MultiError errs) {
        this.config = conf;
        for (InputProviderConfig v : conf.inputProviders) {
            try {
                if (v.name.equals("command")) {
                    providers.add(new CommandInput(v.keyword, v.value, conf));
                } else {
                    providers.add(new WordlistInput(v.keyword, v.value, conf));
                }
                if (!v.encoders.isEmpty()) {
                    encoders.put(v.keyword, new Encoders(List.of(v.encoders.strip().split(" ", -1))));
                }
            } catch (IOException e) {
                errs.add(e.getMessage());
            } catch (IllegalArgumentException e) {
                errs.add(e.getMessage());
            }
        }
    }

    /** Moves to a 1-based position, as -search needs. */
    public void setPosition(int pos) {
        if (config.inputMode.equals("pitchfork")) {
            for (InternalInputProvider p : providers) {
                p.setPosition(pos);
            }
            return;
        }
        reset();
        if (pos > total()) {
            return;
        }
        while (position < pos - 1) {
            next();
            value();
        }
    }

    /** Disables the providers whose keyword is not in the list. */
    public void activateKeywords(List<String> kws) {
        for (InternalInputProvider p : providers) {
            if (!kws.contains(p.keyword())) {
                p.disable();
            }
        }
    }

    /** {@return the keywords of the active providers} */
    public List<String> keywords() {
        List<String> out = new ArrayList<>();
        for (InternalInputProvider p : providers) {
            out.add(p.keyword());
        }
        return out;
    }

    /** {@return the position of the current payload set} */
    public int position() {
        return position;
    }

    /**
     * Advances to the next payload set.
     *
     * @return false when every set was produced
     */
    public boolean next() {
        if (position >= total()) {
            return false;
        }
        position++;
        return true;
    }

    /** {@return the current payload of each active keyword} */
    public Map<String, byte[]> value() {
        Map<String, byte[]> ret = config.inputMode.equals("pitchfork") ? pitchforkValue() : clusterbombValue();
        for (Map.Entry<String, byte[]> e : ret.entrySet()) {
            Encoders enc = encoders.get(e.getKey());
            if (enc != null) {
                try {
                    e.setValue(enc.encode(e.getValue()));
                } catch (Encoders.EncodeException ex) {
                    io.github.ffuf.pkg.output.Stdoutput.OUT.print("ERROR: " + ex.getMessage() + "\n");
                    e.setValue(new byte[0]);
                }
            }
        }
        return ret;
    }

    /** Moves every provider back to the start. */
    public void reset() {
        for (InternalInputProvider p : providers) {
            p.resetPosition();
        }
        position = 0;
        msbIterator = 0;
    }

    private Map<String, byte[]> pitchforkValue() {
        Map<String, byte[]> values = new LinkedHashMap<>();
        for (InternalInputProvider p : providers) {
            if (!p.active()) {
                continue;
            }
            if (!p.next()) {
                p.resetPosition();
            }
            values.put(p.keyword(), p.value());
            p.incrementPosition();
        }
        return values;
    }

    private Map<String, byte[]> clusterbombValue() {
        Map<String, byte[]> values = new LinkedHashMap<>();
        boolean signalNext = false;
        boolean first = true;
        int index = 0;
        for (InternalInputProvider p : providers) {
            if (!p.active()) {
                continue;
            }
            if (signalNext) {
                p.incrementPosition();
                signalNext = false;
            }
            if (!p.next()) {
                if (index == msbIterator) {
                    msbIterator++;
                    clusterbombIteratorReset();
                    return clusterbombValue();
                }
                p.resetPosition();
                signalNext = true;
            }
            values.put(p.keyword(), p.value());
            if (first) {
                p.incrementPosition();
                first = false;
            }
            index++;
        }
        return values;
    }

    private void clusterbombIteratorReset() {
        int index = 0;
        for (InternalInputProvider p : providers) {
            if (!p.active()) {
                continue;
            }
            if (index < msbIterator) {
                p.resetPosition();
            }
            if (index == msbIterator) {
                p.incrementPosition();
            }
            index++;
        }
    }

    /** {@return the number of payload sets} */
    public long total() {
        long count = 0;
        if (config.inputMode.equals("pitchfork")) {
            for (InternalInputProvider p : providers) {
                if (p.active() && p.total() > count) {
                    count = p.total();
                }
            }
        } else {
            count = 1;
            for (InternalInputProvider p : providers) {
                if (p.active()) {
                    count *= p.total();
                }
            }
        }
        return count;
    }
}
