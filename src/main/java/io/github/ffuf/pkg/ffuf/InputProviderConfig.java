package io.github.ffuf.pkg.ffuf;

/** Describes one input source: a wordlist or a command, its keyword and encoders. */
public class InputProviderConfig {
    public final String name;
    public final String value;
    public final String keyword;
    public final String template;
    public String encoders = "";

    /** Creates the configuration of one input provider. */
    public InputProviderConfig(String name, String value, String keyword, String template) {
        this.name = name;
        this.value = value;
        this.keyword = keyword;
        this.template = template;
    }
}
