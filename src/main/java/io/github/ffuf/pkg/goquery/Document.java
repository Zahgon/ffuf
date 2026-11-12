package io.github.ffuf.pkg.goquery;

import java.util.List;

/** Parses a response body into an HTML tree, ready for {@link Selection#find}. */
public final class Document {
    private Document() {
    }

    /**
     * Parses raw bytes with the HTML5 tree construction rules of Go's x/net/html.
     *
     * @param body the response body
     * @return the selection holding the document root, or null when the parser rejects the input (for example when
     *     more than 512 elements are open at once), so callers treat the document as unusable
     */
    public static Selection parse(byte[] body) {
        Node root;
        try {
            root = HtmlParser.parse(body);
        } catch (HtmlParser.ParseException e) {
            // The parse error is the "unusable document" signal; callers only need to skip scraping it.
            return null;
        }
        return new Selection(List.of(root));
    }
}
