package io.github.ffuf.pkg.goquery;

import io.github.ffuf.pkg.goquery.Tokenizer.Token;
import io.github.ffuf.pkg.goquery.Tokenizer.TokenType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The HTML5 tree builder, following the tree construction of golang.org/x/net/html with scripting enabled as
 * html.Parse runs it. Tag comparisons use the tag name where Go uses its atom; the two agree for every tag the
 * algorithm names.
 */
final class HtmlParser {
    /** A document the tree builder rejects; x/net/html's html.Parse reports the same documents as an error. */
    static final class ParseException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ParseException(String msg) {
            super(msg);
        }
    }

    @FunctionalInterface
    private interface InsertionMode {
        boolean apply();
    }

    private static final String WHITESPACE = " \t\r\n\f";
    private static final Node SCOPE_MARKER = new Node(Node.Type.SCOPE_MARKER, "", "", null);

    private static final int DEFAULT_SCOPE = 0;
    private static final int LIST_ITEM_SCOPE = 1;
    private static final int BUTTON_SCOPE = 2;
    private static final int TABLE_SCOPE = 3;
    private static final int TABLE_ROW_SCOPE = 4;
    private static final int TABLE_BODY_SCOPE = 5;

    private static final Map<String, Set<String>> DEFAULT_SCOPE_STOP_TAGS = Map.of(
            "", Set.of("applet", "caption", "html", "table", "td", "th", "marquee", "object", "template", "select"),
            "math", Set.of("annotation-xml", "mi", "mn", "mo", "ms", "mtext"),
            "svg", Set.of("desc", "foreignObject", "title"));

    private static final Set<String> SPECIAL = Set.of("address", "applet", "area", "article", "aside", "base",
            "basefont", "bgsound", "blockquote", "body", "br", "button", "caption", "center", "col", "colgroup", "dd",
            "details", "dir", "div", "dl", "dt", "embed", "fieldset", "figcaption", "figure", "footer", "form", "frame",
            "frameset", "h1", "h2", "h3", "h4", "h5", "h6", "head", "header", "hgroup", "hr", "html", "iframe", "img",
            "input", "keygen", "li", "link", "listing", "main", "marquee", "menu", "meta", "nav", "noembed", "noframes",
            "noscript", "object", "ol", "p", "param", "plaintext", "pre", "script", "section", "select", "source",
            "style", "summary", "table", "tbody", "td", "template", "textarea", "tfoot", "th", "thead", "title", "tr",
            "track", "ul", "wbr", "xmp");

    private static final Set<String> MATH_SPECIAL = Set.of("mi", "mo", "mn", "ms", "mtext", "annotation-xml");
    private static final Set<String> SVG_SPECIAL = Set.of("foreignObject", "desc", "title");
    private static final Set<String> MATH_TEXT_INTEGRATION = Set.of("mi", "mo", "mn", "ms", "mtext");

    private static final Set<String> BREAKOUT = Set.of("b", "big", "blockquote", "body", "br", "center", "code",
            "dd", "div", "dl", "dt", "em", "embed", "h1", "h2", "h3", "h4", "h5", "h6", "head", "hr", "i", "img", "li",
            "listing", "menu", "meta", "nobr", "ol", "p", "pre", "ruby", "s", "small", "span", "strong", "strike",
            "sub", "sup", "table", "tt", "u", "ul", "var");

    private static final Map<String, String> SVG_TAG_NAME_ADJUSTMENTS = Map.ofEntries(
            Map.entry("altglyph", "altGlyph"), Map.entry("altglyphdef", "altGlyphDef"),
            Map.entry("altglyphitem", "altGlyphItem"), Map.entry("animatecolor", "animateColor"),
            Map.entry("animatemotion", "animateMotion"), Map.entry("animatetransform", "animateTransform"),
            Map.entry("clippath", "clipPath"), Map.entry("feblend", "feBlend"),
            Map.entry("fecolormatrix", "feColorMatrix"), Map.entry("fecomponenttransfer", "feComponentTransfer"),
            Map.entry("fecomposite", "feComposite"), Map.entry("feconvolvematrix", "feConvolveMatrix"),
            Map.entry("fediffuselighting", "feDiffuseLighting"), Map.entry("fedisplacementmap", "feDisplacementMap"),
            Map.entry("fedistantlight", "feDistantLight"), Map.entry("feflood", "feFlood"),
            Map.entry("fefunca", "feFuncA"), Map.entry("fefuncb", "feFuncB"), Map.entry("fefuncg", "feFuncG"),
            Map.entry("fefuncr", "feFuncR"), Map.entry("fegaussianblur", "feGaussianBlur"),
            Map.entry("feimage", "feImage"), Map.entry("femerge", "feMerge"), Map.entry("femergenode", "feMergeNode"),
            Map.entry("femorphology", "feMorphology"), Map.entry("feoffset", "feOffset"),
            Map.entry("fepointlight", "fePointLight"), Map.entry("fespecularlighting", "feSpecularLighting"),
            Map.entry("fespotlight", "feSpotLight"), Map.entry("fetile", "feTile"),
            Map.entry("feturbulence", "feTurbulence"), Map.entry("foreignobject", "foreignObject"),
            Map.entry("glyphref", "glyphRef"), Map.entry("lineargradient", "linearGradient"),
            Map.entry("radialgradient", "radialGradient"), Map.entry("textpath", "textPath"));

    private static final Map<String, String> MATHML_ATTRIBUTE_ADJUSTMENTS = Map.of("definitionurl", "definitionURL");

    private static final Map<String, String> SVG_ATTRIBUTE_ADJUSTMENTS = Map.ofEntries(
            Map.entry("attributename", "attributeName"), Map.entry("attributetype", "attributeType"),
            Map.entry("basefrequency", "baseFrequency"), Map.entry("baseprofile", "baseProfile"),
            Map.entry("calcmode", "calcMode"), Map.entry("clippathunits", "clipPathUnits"),
            Map.entry("diffuseconstant", "diffuseConstant"), Map.entry("edgemode", "edgeMode"),
            Map.entry("filterunits", "filterUnits"), Map.entry("glyphref", "glyphRef"),
            Map.entry("gradienttransform", "gradientTransform"), Map.entry("gradientunits", "gradientUnits"),
            Map.entry("kernelmatrix", "kernelMatrix"), Map.entry("kernelunitlength", "kernelUnitLength"),
            Map.entry("keypoints", "keyPoints"), Map.entry("keysplines", "keySplines"),
            Map.entry("keytimes", "keyTimes"), Map.entry("lengthadjust", "lengthAdjust"),
            Map.entry("limitingconeangle", "limitingConeAngle"), Map.entry("markerheight", "markerHeight"),
            Map.entry("markerunits", "markerUnits"), Map.entry("markerwidth", "markerWidth"),
            Map.entry("maskcontentunits", "maskContentUnits"), Map.entry("maskunits", "maskUnits"),
            Map.entry("numoctaves", "numOctaves"), Map.entry("pathlength", "pathLength"),
            Map.entry("patterncontentunits", "patternContentUnits"), Map.entry("patterntransform", "patternTransform"),
            Map.entry("patternunits", "patternUnits"), Map.entry("pointsatx", "pointsAtX"),
            Map.entry("pointsaty", "pointsAtY"), Map.entry("pointsatz", "pointsAtZ"),
            Map.entry("preservealpha", "preserveAlpha"), Map.entry("preserveaspectratio", "preserveAspectRatio"),
            Map.entry("primitiveunits", "primitiveUnits"), Map.entry("refx", "refX"), Map.entry("refy", "refY"),
            Map.entry("repeatcount", "repeatCount"), Map.entry("repeatdur", "repeatDur"),
            Map.entry("requiredextensions", "requiredExtensions"), Map.entry("requiredfeatures", "requiredFeatures"),
            Map.entry("specularconstant", "specularConstant"), Map.entry("specularexponent", "specularExponent"),
            Map.entry("spreadmethod", "spreadMethod"), Map.entry("startoffset", "startOffset"),
            Map.entry("stddeviation", "stdDeviation"), Map.entry("stitchtiles", "stitchTiles"),
            Map.entry("surfacescale", "surfaceScale"), Map.entry("systemlanguage", "systemLanguage"),
            Map.entry("tablevalues", "tableValues"), Map.entry("targetx", "targetX"), Map.entry("targety", "targetY"),
            Map.entry("textlength", "textLength"), Map.entry("viewbox", "viewBox"),
            Map.entry("viewtarget", "viewTarget"), Map.entry("xchannelselector", "xChannelSelector"),
            Map.entry("ychannelselector", "yChannelSelector"), Map.entry("zoomandpan", "zoomAndPan"));

    private static final Set<String> FOREIGN_ATTRIBUTES = Set.of("xlink:actuate", "xlink:arcrole", "xlink:href",
            "xlink:role", "xlink:show", "xlink:title", "xlink:type", "xml:lang", "xml:space", "xmlns:xlink");

    private static final String[] QUIRKY_IDS = {
        "+//silmaril//dtd html pro v0r11 19970101//",
        "-//advasoft ltd//dtd html 3.0 aswedit + extensions//",
        "-//as//dtd html 3.0 aswedit + extensions//",
        "-//ietf//dtd html 2.0 level 1//",
        "-//ietf//dtd html 2.0 level 2//",
        "-//ietf//dtd html 2.0 strict level 1//",
        "-//ietf//dtd html 2.0 strict level 2//",
        "-//ietf//dtd html 2.0 strict//",
        "-//ietf//dtd html 2.0//",
        "-//ietf//dtd html 2.1e//",
        "-//ietf//dtd html 3.0//",
        "-//ietf//dtd html 3.2 final//",
        "-//ietf//dtd html 3.2//",
        "-//ietf//dtd html 3//",
        "-//ietf//dtd html level 0//",
        "-//ietf//dtd html level 1//",
        "-//ietf//dtd html level 2//",
        "-//ietf//dtd html level 3//",
        "-//ietf//dtd html strict level 0//",
        "-//ietf//dtd html strict level 1//",
        "-//ietf//dtd html strict level 2//",
        "-//ietf//dtd html strict level 3//",
        "-//ietf//dtd html strict//",
        "-//ietf//dtd html//",
        "-//metrius//dtd metrius presentational//",
        "-//microsoft//dtd internet explorer 2.0 html strict//",
        "-//microsoft//dtd internet explorer 2.0 html//",
        "-//microsoft//dtd internet explorer 2.0 tables//",
        "-//microsoft//dtd internet explorer 3.0 html strict//",
        "-//microsoft//dtd internet explorer 3.0 html//",
        "-//microsoft//dtd internet explorer 3.0 tables//",
        "-//netscape comm. corp.//dtd html//",
        "-//netscape comm. corp.//dtd strict html//",
        "-//o'reilly and associates//dtd html 2.0//",
        "-//o'reilly and associates//dtd html extended 1.0//",
        "-//o'reilly and associates//dtd html extended relaxed 1.0//",
        "-//softquad software//dtd hotmetal pro 6.0::19990601::extensions to html 4.0//",
        "-//softquad//dtd hotmetal pro 4.0::19971010::extensions to html 4.0//",
        "-//spyglass//dtd html 2.0 extended//",
        "-//sq//dtd html 2.0 hotmetal + extensions//",
        "-//sun microsystems corp.//dtd hotjava html//",
        "-//sun microsystems corp.//dtd hotjava strict html//",
        "-//w3c//dtd html 3 1995-03-24//",
        "-//w3c//dtd html 3.2 draft//",
        "-//w3c//dtd html 3.2 final//",
        "-//w3c//dtd html 3.2//",
        "-//w3c//dtd html 3.2s draft//",
        "-//w3c//dtd html 4.0 frameset//",
        "-//w3c//dtd html 4.0 transitional//",
        "-//w3c//dtd html experimental 19960712//",
        "-//w3c//dtd html experimental 970421//",
        "-//w3c//dtd w3 html//",
        "-//w3o//dtd w3 html 3.0//",
        "-//webtechs//dtd mozilla html 2.0//",
        "-//webtechs//dtd mozilla html//",
    };

    private static final Comparator<Node.Attribute> ATTR_ORDER = Comparator
            .comparing((Node.Attribute a) -> a.namespace)
            .thenComparing(a -> a.key)
            .thenComparing(a -> a.val);

    private final Tokenizer tokenizer;
    private Token tok;
    private boolean hasSelfClosingToken;
    private final Node doc = new Node(Node.Type.DOCUMENT, "", "", null);
    private final List<Node> oe = new ArrayList<>();
    private final List<Node> afe = new ArrayList<>();
    private Node head;
    private Node form;
    private final boolean scripting = true;
    private boolean framesetOK = true;
    private final List<InsertionMode> templateStack = new ArrayList<>();
    private InsertionMode im;
    private InsertionMode originalIM;
    private boolean fosterParenting;
    private boolean quirks;

    private final InsertionMode initialIM = this::initialIM;
    private final InsertionMode beforeHTMLIM = this::beforeHTMLIM;
    private final InsertionMode beforeHeadIM = this::beforeHeadIM;
    private final InsertionMode inHeadIM = this::inHeadIM;
    private final InsertionMode inHeadNoscriptIM = this::inHeadNoscriptIM;
    private final InsertionMode afterHeadIM = this::afterHeadIM;
    private final InsertionMode inBodyIM = this::inBodyIM;
    private final InsertionMode textIM = this::textIM;
    private final InsertionMode inTableIM = this::inTableIM;
    private final InsertionMode inCaptionIM = this::inCaptionIM;
    private final InsertionMode inColumnGroupIM = this::inColumnGroupIM;
    private final InsertionMode inTableBodyIM = this::inTableBodyIM;
    private final InsertionMode inRowIM = this::inRowIM;
    private final InsertionMode inCellIM = this::inCellIM;
    private final InsertionMode inTemplateIM = this::inTemplateIM;
    private final InsertionMode afterBodyIM = this::afterBodyIM;
    private final InsertionMode inFramesetIM = this::inFramesetIM;
    private final InsertionMode afterFramesetIM = this::afterFramesetIM;
    private final InsertionMode afterAfterBodyIM = this::afterAfterBodyIM;
    private final InsertionMode afterAfterFramesetIM = () -> afterAfterFramesetIM();
    private final InsertionMode ignoreTheRemainingTokens = () -> true;

    private HtmlParser(byte[] input) {
        tokenizer = new Tokenizer(input);
        im = initialIM;
    }

    /** Builds the tree of a document; throws ParseException for a document x/net/html's html.Parse rejects. */
    static Node parse(byte[] input) {
        HtmlParser p = new HtmlParser(input);
        p.run();
        return p.doc;
    }

    private void run() {
        boolean done = false;
        while (!done) {
            Node n = top(oe);
            tokenizer.allowCDATA(n != null && !n.namespace.isEmpty());
            tokenizer.next();
            tok = tokenizer.token();
            if (tok.type == TokenType.ERROR) {
                done = tokenizer.atEOF();
            }
            parseCurrentToken();
        }
    }

    // ---- stacks ----

    private static Node top(List<Node> s) {
        return s.isEmpty() ? null : s.get(s.size() - 1);
    }

    private static Node pop(List<Node> s) {
        return s.remove(s.size() - 1);
    }

    private static int index(List<Node> s, Node n) {
        for (int i = s.size() - 1; i >= 0; i--) {
            if (s.get(i) == n) {
                return i;
            }
        }
        return -1;
    }

    private static boolean contains(List<Node> s, String atom) {
        for (Node n : s) {
            if (n.atom.equals(atom) && n.namespace.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static void remove(List<Node> s, Node n) {
        int i = index(s, n);
        if (i != -1) {
            s.remove(i);
        }
    }

    private static void truncate(List<Node> s, int len) {
        while (s.size() > len) {
            s.remove(s.size() - 1);
        }
    }

    private Node top() {
        Node n = top(oe);
        return n != null ? n : doc;
    }

    private boolean tokIs(String... atoms) {
        for (String a : atoms) {
            if (tok.atom.equals(a)) {
                return true;
            }
        }
        return false;
    }

    private static boolean is(Node n, String... atoms) {
        for (String a : atoms) {
            if (n.atom.equals(a)) {
                return true;
            }
        }
        return false;
    }

    // ---- scope ----

    private boolean popUntil(int s, String... matchTags) {
        int i = indexOfElementInScope(s, matchTags);
        if (i != -1) {
            truncate(oe, i);
            return true;
        }
        return false;
    }

    private int indexOfElementInScope(int s, String... matchTags) {
        for (int i = oe.size() - 1; i >= 0; i--) {
            Node n = oe.get(i);
            String tagAtom = n.atom;
            if (n.namespace.isEmpty()) {
                for (String t : matchTags) {
                    if (t.equals(tagAtom)) {
                        return i;
                    }
                }
                switch (s) {
                    case DEFAULT_SCOPE -> {
                    }
                    case LIST_ITEM_SCOPE -> {
                        if (tagAtom.equals("ol") || tagAtom.equals("ul")) {
                            return -1;
                        }
                    }
                    case BUTTON_SCOPE -> {
                        if (tagAtom.equals("button")) {
                            return -1;
                        }
                    }
                    case TABLE_SCOPE -> {
                        if (tagAtom.equals("html") || tagAtom.equals("table") || tagAtom.equals("template")) {
                            return -1;
                        }
                    }
                    default -> throw new ParseException("html: internal error: indexOfElementInScope unknown scope: " + s);
                }
            }
            if (s == DEFAULT_SCOPE || s == LIST_ITEM_SCOPE || s == BUTTON_SCOPE) {
                Set<String> stop = DEFAULT_SCOPE_STOP_TAGS.get(n.namespace);
                if (stop != null && stop.contains(tagAtom)) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private boolean elementInScope(int s, String... matchTags) {
        return indexOfElementInScope(s, matchTags) != -1;
    }

    private void clearStackToContext(int s) {
        for (int i = oe.size() - 1; i >= 0; i--) {
            String tagAtom = oe.get(i).atom;
            boolean stop = switch (s) {
                case TABLE_SCOPE -> tagAtom.equals("html") || tagAtom.equals("table") || tagAtom.equals("template");
                case TABLE_ROW_SCOPE -> tagAtom.equals("html") || tagAtom.equals("tr") || tagAtom.equals("template");
                case TABLE_BODY_SCOPE -> tagAtom.equals("html") || tagAtom.equals("tbody") || tagAtom.equals("tfoot")
                        || tagAtom.equals("thead") || tagAtom.equals("template");
                default -> throw new ParseException("html: internal error: clearStackToContext unknown scope: " + s);
            };
            if (stop) {
                truncate(oe, i + 1);
                return;
            }
        }
    }

    private void parseGenericRawTextElement() {
        addElement();
        originalIM = im;
        im = textIM;
    }

    private void generateImpliedEndTags(String... exceptions) {
        int i;
        loop:
        for (i = oe.size() - 1; i >= 0; i--) {
            Node n = oe.get(i);
            if (n.type != Node.Type.ELEMENT) {
                break;
            }
            if (is(n, "dd", "dt", "li", "optgroup", "option", "p", "rb", "rp", "rt", "rtc")) {
                for (String except : exceptions) {
                    if (n.data.equals(except)) {
                        break loop;
                    }
                }
                continue;
            }
            break;
        }
        truncate(oe, i + 1);
    }

    // ---- tree construction ----

    private void addChild(Node n) {
        if (shouldFosterParent()) {
            fosterParent(n);
        } else {
            top().appendChild(n);
        }
        if (n.type == Node.Type.ELEMENT) {
            insertOpenElement(n);
        }
    }

    private void insertOpenElement(Node n) {
        oe.add(n);
        if (oe.size() > 512) {
            throw new ParseException("html: open stack of elements exceeds 512 nodes");
        }
    }

    private boolean shouldFosterParent() {
        return fosterParenting && is(top(), "table", "tbody", "tfoot", "thead", "tr");
    }

    private void fosterParent(Node n) {
        Node table = null;
        Node parent;
        Node prev;
        Node template = null;
        int i;
        for (i = oe.size() - 1; i >= 0; i--) {
            if (oe.get(i).atom.equals("table")) {
                table = oe.get(i);
                break;
            }
        }
        int j;
        for (j = oe.size() - 1; j >= 0; j--) {
            if (oe.get(j).atom.equals("template")) {
                template = oe.get(j);
                break;
            }
        }
        if (template != null && (table == null || j > i)) {
            template.appendChild(n);
            return;
        }
        if (table == null) {
            parent = oe.get(0);
        } else {
            parent = table.parent;
        }
        if (parent == null) {
            parent = oe.get(i - 1);
        }
        if (table != null) {
            prev = table.prevSibling;
        } else {
            prev = parent.lastChild;
        }
        if (prev != null && prev.type == Node.Type.TEXT && n.type == Node.Type.TEXT) {
            prev.data += n.data;
            return;
        }
        parent.insertBefore(n, table);
    }

    private void addText(String text) {
        if (text.isEmpty()) {
            return;
        }
        if (shouldFosterParent()) {
            fosterParent(Node.text(text));
            return;
        }
        Node t = top();
        Node n = t.lastChild;
        if (n != null && n.type == Node.Type.TEXT) {
            n.data += text;
            return;
        }
        addChild(Node.text(text));
    }

    private void addElement() {
        addChild(new Node(Node.Type.ELEMENT, tok.atom, tok.data, tok.attr));
    }

    private void addFormattingElement() {
        String tagAtom = tok.atom;
        addElement();
        Node top = top();
        List<Node.Attribute> attr = top.attr;
        attr.sort(ATTR_ORDER);
        int identicalElements = 0;
        for (int i = afe.size() - 1; i >= 0; i--) {
            Node n = afe.get(i);
            if (n.type == Node.Type.SCOPE_MARKER) {
                break;
            }
            if (n.type != Node.Type.ELEMENT || !n.namespace.isEmpty() || !n.atom.equals(tagAtom)) {
                continue;
            }
            if (!n.attr.equals(attr)) {
                continue;
            }
            identicalElements++;
            if (identicalElements >= 3) {
                remove(afe, n);
            }
        }
        afe.add(top);
    }

    private void clearActiveFormattingElements() {
        while (true) {
            Node n = pop(afe);
            if (afe.isEmpty() || n.type == Node.Type.SCOPE_MARKER) {
                return;
            }
        }
    }

    private void reconstructActiveFormattingElements() {
        Node n = top(afe);
        if (n == null) {
            return;
        }
        if (n.type == Node.Type.SCOPE_MARKER || index(oe, n) != -1) {
            return;
        }
        int i = afe.size() - 1;
        while (n.type != Node.Type.SCOPE_MARKER && index(oe, n) == -1) {
            if (i == 0) {
                i = -1;
                break;
            }
            i--;
            n = afe.get(i);
        }
        while (true) {
            i++;
            Node clone = afe.get(i).copyNode();
            addChild(clone);
            afe.set(i, clone);
            if (i == afe.size() - 1) {
                break;
            }
        }
    }

    private void acknowledgeSelfClosingTag() {
        hasSelfClosingToken = false;
    }

    private void setOriginalIM() {
        if (originalIM != null) {
            throw new ParseException("html: bad parser state: originalIM was set twice");
        }
        originalIM = im;
    }

    private void resetInsertionMode() {
        for (int i = oe.size() - 1; i >= 0; i--) {
            Node n = oe.get(i);
            boolean last = i == 0;
            switch (n.atom) {
                case "td", "th" -> im = inCellIM;
                case "tr" -> im = inRowIM;
                case "tbody", "thead", "tfoot" -> im = inTableBodyIM;
                case "caption" -> im = inCaptionIM;
                case "colgroup" -> im = inColumnGroupIM;
                case "table" -> im = inTableIM;
                case "template" -> {
                    if (!n.namespace.isEmpty()) {
                        continue;
                    }
                    im = templateStack.isEmpty() ? null : templateStack.get(templateStack.size() - 1);
                }
                case "head" -> im = inHeadIM;
                case "body" -> im = inBodyIM;
                case "frameset" -> im = inFramesetIM;
                case "html" -> im = head == null ? beforeHeadIM : afterHeadIM;
                default -> {
                    if (last) {
                        im = inBodyIM;
                        return;
                    }
                    continue;
                }
            }
            return;
        }
    }

    private void addComment(Node parent) {
        parent.appendChild(Node.comment(tok.data));
    }

    // ---- insertion modes ----

    private boolean initialIM() {
        switch (tok.type) {
            case TEXT -> {
                tok.data = GoStrings.trimLeft(tok.data, WHITESPACE);
                if (tok.data.isEmpty()) {
                    return true;
                }
            }
            case COMMENT -> {
                addComment(doc);
                return true;
            }
            case DOCTYPE -> {
                doc.appendChild(parseDoctype(tok.data));
                im = beforeHTMLIM;
                return true;
            }
            default -> {
            }
        }
        quirks = true;
        im = beforeHTMLIM;
        return false;
    }

    private boolean beforeHTMLIM() {
        switch (tok.type) {
            case DOCTYPE -> {
                return true;
            }
            case TEXT -> {
                tok.data = GoStrings.trimLeft(tok.data, WHITESPACE);
                if (tok.data.isEmpty()) {
                    return true;
                }
            }
            case START_TAG -> {
                if (tokIs("html")) {
                    addElement();
                    im = beforeHeadIM;
                    return true;
                }
            }
            case END_TAG -> {
                if (tokIs("head", "body", "html", "br")) {
                    parseImpliedToken(TokenType.START_TAG, "html");
                    return false;
                }
                return true;
            }
            case COMMENT -> {
                addComment(doc);
                return true;
            }
            default -> {
            }
        }
        parseImpliedToken(TokenType.START_TAG, "html");
        return false;
    }

    private boolean beforeHeadIM() {
        switch (tok.type) {
            case TEXT -> {
                tok.data = GoStrings.trimLeft(tok.data, WHITESPACE);
                if (tok.data.isEmpty()) {
                    return true;
                }
            }
            case START_TAG -> {
                if (tokIs("head")) {
                    addElement();
                    head = top();
                    im = inHeadIM;
                    return true;
                } else if (tokIs("html")) {
                    return inBodyIM();
                }
            }
            case END_TAG -> {
                if (tokIs("head", "body", "html", "br")) {
                    parseImpliedToken(TokenType.START_TAG, "head");
                    return false;
                }
                return true;
            }
            case COMMENT -> {
                addChild(Node.comment(tok.data));
                return true;
            }
            case DOCTYPE -> {
                return true;
            }
            default -> {
            }
        }
        parseImpliedToken(TokenType.START_TAG, "head");
        return false;
    }

    private boolean inHeadIM() {
        switch (tok.type) {
            case TEXT -> {
                String s = GoStrings.trimLeft(tok.data, WHITESPACE);
                if (s.length() < tok.data.length()) {
                    addText(tok.data.substring(0, tok.data.length() - s.length()));
                    if (s.isEmpty()) {
                        return true;
                    }
                    tok.data = s;
                }
            }
            case START_TAG -> {
                switch (tok.atom) {
                    case "html" -> {
                        return inBodyIM();
                    }
                    case "base", "basefont", "bgsound", "link", "meta" -> {
                        addElement();
                        pop(oe);
                        acknowledgeSelfClosingTag();
                        return true;
                    }
                    case "noscript" -> {
                        if (scripting) {
                            parseGenericRawTextElement();
                            return true;
                        }
                        addElement();
                        im = inHeadNoscriptIM;
                        tokenizer.nextIsNotRawText();
                        return true;
                    }
                    case "script", "title" -> {
                        addElement();
                        setOriginalIM();
                        im = textIM;
                        return true;
                    }
                    case "noframes", "style" -> {
                        parseGenericRawTextElement();
                        return true;
                    }
                    case "head" -> {
                        return true;
                    }
                    case "template" -> {
                        for (Node e : oe) {
                            if (!e.namespace.isEmpty()) {
                                im = ignoreTheRemainingTokens;
                                return true;
                            }
                        }
                        addElement();
                        afe.add(SCOPE_MARKER);
                        framesetOK = false;
                        im = inTemplateIM;
                        templateStack.add(inTemplateIM);
                        return true;
                    }
                    default -> {
                    }
                }
            }
            case END_TAG -> {
                switch (tok.atom) {
                    case "head" -> {
                        pop(oe);
                        im = afterHeadIM;
                        return true;
                    }
                    case "body", "html", "br" -> {
                        parseImpliedToken(TokenType.END_TAG, "head");
                        return false;
                    }
                    case "template" -> {
                        if (!contains(oe, "template")) {
                            return true;
                        }
                        generateImpliedEndTags();
                        for (int i = oe.size() - 1; i >= 0; i--) {
                            Node n = oe.get(i);
                            if (n.namespace.isEmpty() && n.atom.equals("template")) {
                                truncate(oe, i);
                                break;
                            }
                        }
                        clearActiveFormattingElements();
                        templateStack.remove(templateStack.size() - 1);
                        resetInsertionMode();
                        return true;
                    }
                    default -> {
                        return true;
                    }
                }
            }
            case COMMENT -> {
                addChild(Node.comment(tok.data));
                return true;
            }
            case DOCTYPE -> {
                return true;
            }
            default -> {
            }
        }
        parseImpliedToken(TokenType.END_TAG, "head");
        return false;
    }

    private boolean inHeadNoscriptIM() {
        switch (tok.type) {
            case DOCTYPE -> {
                return true;
            }
            case START_TAG -> {
                switch (tok.atom) {
                    case "html" -> {
                        return inBodyIM();
                    }
                    case "basefont", "bgsound", "link", "meta", "noframes", "style" -> {
                        return inHeadIM();
                    }
                    case "head" -> {
                        return true;
                    }
                    case "noscript" -> {
                        tokenizer.nextIsNotRawText();
                        return true;
                    }
                    default -> {
                    }
                }
            }
            case END_TAG -> {
                if (!tokIs("noscript", "br")) {
                    return true;
                }
            }
            case TEXT -> {
                if (GoStrings.trimLeft(tok.data, WHITESPACE).isEmpty()) {
                    return inHeadIM();
                }
            }
            case COMMENT -> {
                return inHeadIM();
            }
            default -> {
            }
        }
        pop(oe);
        if (!top().atom.equals("head")) {
            throw new ParseException("html: the new current node will be a head element.");
        }
        im = inHeadIM;
        return tok.atom.equals("noscript");
    }

    private boolean afterHeadIM() {
        switch (tok.type) {
            case TEXT -> {
                String s = GoStrings.trimLeft(tok.data, WHITESPACE);
                if (s.length() < tok.data.length()) {
                    addText(tok.data.substring(0, tok.data.length() - s.length()));
                    if (s.isEmpty()) {
                        return true;
                    }
                    tok.data = s;
                }
            }
            case START_TAG -> {
                switch (tok.atom) {
                    case "html" -> {
                        return inBodyIM();
                    }
                    case "body" -> {
                        addElement();
                        framesetOK = false;
                        im = inBodyIM;
                        return true;
                    }
                    case "frameset" -> {
                        addElement();
                        im = inFramesetIM;
                        return true;
                    }
                    case "base", "basefont", "bgsound", "link", "meta", "noframes", "script", "style", "template",
                            "title" -> {
                        insertOpenElement(head);
                        Node h = head;
                        try {
                            return inHeadIM();
                        } finally {
                            remove(oe, h);
                        }
                    }
                    case "head" -> {
                        return true;
                    }
                    default -> {
                    }
                }
            }
            case END_TAG -> {
                switch (tok.atom) {
                    case "body", "html", "br" -> {
                    }
                    case "template" -> {
                        return inHeadIM();
                    }
                    default -> {
                        return true;
                    }
                }
            }
            case COMMENT -> {
                addChild(Node.comment(tok.data));
                return true;
            }
            case DOCTYPE -> {
                return true;
            }
            default -> {
            }
        }
        parseImpliedToken(TokenType.START_TAG, "body");
        framesetOK = true;
        return tok.type == TokenType.ERROR;
    }

    private static void copyAttributes(Node dst, Token src) {
        if (src.attr == null || src.attr.isEmpty()) {
            return;
        }
        Set<String> keys = new HashSet<>();
        for (Node.Attribute t : dst.attr) {
            keys.add(t.key);
        }
        for (Node.Attribute t : src.attr) {
            if (keys.add(t.key)) {
                dst.attr.add(t);
            }
        }
    }

    private static boolean isSpecialElement(Node e) {
        switch (e.namespace) {
            case "", "html" -> {
                return SPECIAL.contains(e.data);
            }
            case "math" -> {
                return MATH_SPECIAL.contains(e.data);
            }
            case "svg" -> {
                return SVG_SPECIAL.contains(e.data);
            }
            default -> {
                return false;
            }
        }
    }

    private boolean inBodyIM() {
        switch (tok.type) {
            case TEXT -> {
                String d = tok.data;
                Node n = top(oe);
                if (is(n, "pre", "listing") && n.firstChild == null) {
                    if (!d.isEmpty() && d.charAt(0) == '\r') {
                        d = d.substring(1);
                    }
                    if (!d.isEmpty() && d.charAt(0) == '\n') {
                        d = d.substring(1);
                    }
                }
                d = d.replace("\0", "");
                if (d.isEmpty()) {
                    return true;
                }
                reconstructActiveFormattingElements();
                addText(d);
                if (framesetOK && !GoStrings.trimLeft(d, WHITESPACE).isEmpty()) {
                    framesetOK = false;
                }
            }
            case START_TAG -> {
                return inBodyStartTag();
            }
            case END_TAG -> {
                return inBodyEndTag();
            }
            case COMMENT -> addChild(Node.comment(tok.data));
            case ERROR -> {
                if (!templateStack.isEmpty()) {
                    im = inTemplateIM;
                    return false;
                }
                for (Node e : oe) {
                    if (!is(e, "dd", "dt", "li", "optgroup", "option", "p", "rb", "rp", "rt", "rtc", "tbody", "td",
                            "tfoot", "th", "thead", "tr", "body", "html")) {
                        return true;
                    }
                }
            }
            default -> {
            }
        }
        return true;
    }

    private boolean inBodyStartTag() {
        switch (tok.atom) {
            case "html" -> {
                if (contains(oe, "template")) {
                    return true;
                }
                copyAttributes(oe.get(0), tok);
            }
            case "base", "basefont", "bgsound", "link", "meta", "noframes", "script", "style", "template", "title" -> {
                return inHeadIM();
            }
            case "body" -> {
                if (contains(oe, "template")) {
                    return true;
                }
                if (oe.size() >= 2) {
                    Node body = oe.get(1);
                    if (body.type == Node.Type.ELEMENT && body.atom.equals("body")) {
                        framesetOK = false;
                        copyAttributes(body, tok);
                    }
                }
            }
            case "frameset" -> {
                if (!framesetOK || oe.size() < 2 || !oe.get(1).atom.equals("body")) {
                    return true;
                }
                Node body = oe.get(1);
                if (body.parent != null) {
                    body.parent.removeChild(body);
                }
                truncate(oe, 1);
                addElement();
                im = inFramesetIM;
                return true;
            }
            case "address", "article", "aside", "blockquote", "center", "details", "dialog", "dir", "div", "dl",
                    "fieldset", "figcaption", "figure", "footer", "header", "hgroup", "main", "menu", "nav", "ol", "p",
                    "search", "section", "summary", "ul" -> {
                popUntil(BUTTON_SCOPE, "p");
                addElement();
            }
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                popUntil(BUTTON_SCOPE, "p");
                if (is(top(), "h1", "h2", "h3", "h4", "h5", "h6")) {
                    pop(oe);
                }
                addElement();
            }
            case "pre", "listing" -> {
                popUntil(BUTTON_SCOPE, "p");
                addElement();
                framesetOK = false;
            }
            case "form" -> {
                if (form != null && !contains(oe, "template")) {
                    return true;
                }
                popUntil(BUTTON_SCOPE, "p");
                addElement();
                if (!contains(oe, "template")) {
                    form = top();
                }
            }
            case "li" -> {
                framesetOK = false;
                for (int i = oe.size() - 1; i >= 0; i--) {
                    Node node = oe.get(i);
                    if (node.atom.equals("li")) {
                        truncate(oe, i);
                    } else if (is(node, "address", "div", "p") || !isSpecialElement(node)) {
                        continue;
                    }
                    break;
                }
                popUntil(BUTTON_SCOPE, "p");
                addElement();
            }
            case "dd", "dt" -> {
                framesetOK = false;
                for (int i = oe.size() - 1; i >= 0; i--) {
                    Node node = oe.get(i);
                    if (is(node, "dd", "dt")) {
                        truncate(oe, i);
                    } else if (is(node, "address", "div", "p") || !isSpecialElement(node)) {
                        continue;
                    }
                    break;
                }
                popUntil(BUTTON_SCOPE, "p");
                addElement();
            }
            case "plaintext" -> {
                popUntil(BUTTON_SCOPE, "p");
                addElement();
            }
            case "button" -> {
                if (elementInScope(DEFAULT_SCOPE, "button")) {
                    generateImpliedEndTags();
                    popUntil(DEFAULT_SCOPE, "button");
                }
                reconstructActiveFormattingElements();
                addElement();
                framesetOK = false;
            }
            case "a" -> {
                for (int i = afe.size() - 1; i >= 0 && afe.get(i).type != Node.Type.SCOPE_MARKER; i--) {
                    Node n = afe.get(i);
                    if (n.type == Node.Type.ELEMENT && n.atom.equals("a")) {
                        inBodyEndTagFormatting("a", "a");
                        remove(oe, n);
                        remove(afe, n);
                        break;
                    }
                }
                reconstructActiveFormattingElements();
                addFormattingElement();
            }
            case "b", "big", "code", "em", "font", "i", "s", "small", "strike", "strong", "tt", "u" -> {
                reconstructActiveFormattingElements();
                addFormattingElement();
            }
            case "nobr" -> {
                reconstructActiveFormattingElements();
                if (elementInScope(DEFAULT_SCOPE, "nobr")) {
                    inBodyEndTagFormatting("nobr", "nobr");
                    reconstructActiveFormattingElements();
                }
                addFormattingElement();
            }
            case "applet", "marquee", "object" -> {
                reconstructActiveFormattingElements();
                addElement();
                afe.add(SCOPE_MARKER);
                framesetOK = false;
            }
            case "table" -> {
                if (!quirks) {
                    popUntil(BUTTON_SCOPE, "p");
                }
                addElement();
                framesetOK = false;
                im = inTableIM;
                return true;
            }
            case "area", "br", "embed", "img", "keygen", "wbr" -> {
                reconstructActiveFormattingElements();
                addElement();
                pop(oe);
                acknowledgeSelfClosingTag();
                framesetOK = false;
            }
            case "input" -> {
                popUntil(DEFAULT_SCOPE, "select");
                reconstructActiveFormattingElements();
                addElement();
                pop(oe);
                acknowledgeSelfClosingTag();
                if (tok.attr != null) {
                    for (Node.Attribute t : tok.attr) {
                        if (t.key.equals("type") && GoStrings.equalFold(t.val, "hidden")) {
                            return true;
                        }
                    }
                }
                framesetOK = false;
            }
            case "param", "source", "track" -> {
                addElement();
                pop(oe);
                acknowledgeSelfClosingTag();
            }
            case "hr" -> {
                if (elementInScope(BUTTON_SCOPE, "p")) {
                    generateImpliedEndTags("p");
                    popUntil(DEFAULT_SCOPE, "p");
                }
                if (elementInScope(DEFAULT_SCOPE, "select")) {
                    generateImpliedEndTags();
                }
                addElement();
                pop(oe);
                acknowledgeSelfClosingTag();
                framesetOK = false;
            }
            case "image" -> {
                tok.atom = "img";
                tok.data = "img";
                return false;
            }
            case "textarea" -> {
                addElement();
                setOriginalIM();
                framesetOK = false;
                im = textIM;
            }
            case "xmp" -> {
                popUntil(BUTTON_SCOPE, "p");
                reconstructActiveFormattingElements();
                framesetOK = false;
                parseGenericRawTextElement();
            }
            case "iframe" -> {
                framesetOK = false;
                parseGenericRawTextElement();
            }
            case "noembed" -> parseGenericRawTextElement();
            case "noscript" -> {
                if (scripting) {
                    parseGenericRawTextElement();
                    return true;
                }
                reconstructActiveFormattingElements();
                addElement();
                tokenizer.nextIsNotRawText();
            }
            case "select" -> {
                if (popUntil(DEFAULT_SCOPE, "select")) {
                    return true;
                }
                reconstructActiveFormattingElements();
                addElement();
                framesetOK = false;
                return true;
            }
            case "option" -> {
                if (elementInScope(DEFAULT_SCOPE, "select")) {
                    generateImpliedEndTags("optgroup");
                } else if (top().atom.equals("option")) {
                    pop(oe);
                }
                reconstructActiveFormattingElements();
                addElement();
            }
            case "optgroup" -> {
                if (elementInScope(DEFAULT_SCOPE, "select")) {
                    generateImpliedEndTags();
                } else if (top().atom.equals("option")) {
                    pop(oe);
                }
                reconstructActiveFormattingElements();
                addElement();
            }
            case "rb", "rtc" -> {
                if (elementInScope(DEFAULT_SCOPE, "ruby")) {
                    generateImpliedEndTags();
                }
                addElement();
            }
            case "rp", "rt" -> {
                if (elementInScope(DEFAULT_SCOPE, "ruby")) {
                    generateImpliedEndTags("rtc");
                }
                addElement();
            }
            case "math", "svg" -> {
                reconstructActiveFormattingElements();
                if (tok.atom.equals("math")) {
                    adjustAttributeNames(tok.attr, MATHML_ATTRIBUTE_ADJUSTMENTS);
                } else {
                    adjustAttributeNames(tok.attr, SVG_ATTRIBUTE_ADJUSTMENTS);
                }
                adjustForeignAttributes(tok.attr);
                addElement();
                top().namespace = tok.data;
                if (hasSelfClosingToken) {
                    pop(oe);
                    acknowledgeSelfClosingTag();
                }
                return true;
            }
            case "caption", "col", "colgroup", "frame", "head", "tbody", "td", "tfoot", "th", "thead", "tr" -> {
            }
            default -> {
                reconstructActiveFormattingElements();
                addElement();
            }
        }
        return true;
    }

    private boolean inBodyEndTag() {
        switch (tok.atom) {
            case "body" -> {
                if (elementInScope(DEFAULT_SCOPE, "body")) {
                    im = afterBodyIM;
                }
            }
            case "html" -> {
                if (elementInScope(DEFAULT_SCOPE, "body")) {
                    parseImpliedToken(TokenType.END_TAG, "body");
                    return false;
                }
                return true;
            }
            case "address", "article", "aside", "blockquote", "button", "center", "details", "dialog", "dir", "div",
                    "dl", "fieldset", "figcaption", "figure", "footer", "header", "hgroup", "listing", "main", "menu",
                    "nav", "ol", "pre", "search", "section", "select", "summary", "ul" -> {
                if (!elementInScope(DEFAULT_SCOPE, tok.atom)) {
                    return true;
                }
                generateImpliedEndTags();
                popUntil(DEFAULT_SCOPE, tok.atom);
            }
            case "form" -> {
                if (contains(oe, "template")) {
                    int i = indexOfElementInScope(DEFAULT_SCOPE, "form");
                    if (i == -1) {
                        return true;
                    }
                    generateImpliedEndTags();
                    if (!oe.get(i).atom.equals("form")) {
                        return true;
                    }
                    popUntil(DEFAULT_SCOPE, "form");
                } else {
                    Node node = form;
                    form = null;
                    int i = indexOfElementInScope(DEFAULT_SCOPE, "form");
                    if (node == null || i == -1 || oe.get(i) != node) {
                        return true;
                    }
                    generateImpliedEndTags();
                    remove(oe, node);
                }
            }
            case "p" -> {
                if (!elementInScope(BUTTON_SCOPE, "p")) {
                    parseImpliedToken(TokenType.START_TAG, "p");
                }
                popUntil(BUTTON_SCOPE, "p");
            }
            case "li" -> popUntil(LIST_ITEM_SCOPE, "li");
            case "dd", "dt" -> popUntil(DEFAULT_SCOPE, tok.atom);
            case "h1", "h2", "h3", "h4", "h5", "h6" -> popUntil(DEFAULT_SCOPE, "h1", "h2", "h3", "h4", "h5", "h6");
            case "a", "b", "big", "code", "em", "font", "i", "nobr", "s", "small", "strike", "strong", "tt", "u" ->
                inBodyEndTagFormatting(tok.atom, tok.data);
            case "applet", "marquee", "object" -> {
                if (popUntil(DEFAULT_SCOPE, tok.atom)) {
                    clearActiveFormattingElements();
                }
            }
            case "br" -> {
                tok.type = TokenType.START_TAG;
                return false;
            }
            case "template" -> {
                return inHeadIM();
            }
            default -> inBodyEndTagOther(tok.atom, tok.data);
        }
        return true;
    }

    private void inBodyEndTagFormatting(String tagAtom, String tagName) {
        Node current = top(oe);
        if (current.data.equals(tagName) && index(afe, current) == -1) {
            pop(oe);
            return;
        }
        for (int iter = 0; iter < 8; iter++) {
            Node formattingElement = null;
            for (int j = afe.size() - 1; j >= 0; j--) {
                if (afe.get(j).type == Node.Type.SCOPE_MARKER) {
                    break;
                }
                if (afe.get(j).atom.equals(tagAtom)) {
                    formattingElement = afe.get(j);
                    break;
                }
            }
            if (formattingElement == null) {
                inBodyEndTagOther(tagAtom, tagName);
                return;
            }
            int feIndex = index(oe, formattingElement);
            if (feIndex == -1) {
                remove(afe, formattingElement);
                return;
            }
            if (!elementInScope(DEFAULT_SCOPE, tagAtom)) {
                return;
            }
            Node furthestBlock = null;
            for (int k = feIndex; k < oe.size(); k++) {
                if (isSpecialElement(oe.get(k))) {
                    furthestBlock = oe.get(k);
                    break;
                }
            }
            if (furthestBlock == null) {
                Node e = pop(oe);
                while (e != formattingElement) {
                    e = pop(oe);
                }
                remove(afe, e);
                return;
            }
            Node commonAncestor = oe.get(feIndex - 1);
            int bookmark = index(afe, formattingElement);
            Node lastNode = furthestBlock;
            Node node;
            int x = index(oe, furthestBlock);
            int j = 0;
            while (true) {
                j++;
                x--;
                node = oe.get(x);
                if (node == formattingElement) {
                    break;
                }
                int ni = index(afe, node);
                if (j > 3 && ni > -1) {
                    remove(afe, node);
                    if (ni <= bookmark) {
                        bookmark--;
                    }
                    continue;
                }
                if (index(afe, node) == -1) {
                    remove(oe, node);
                    continue;
                }
                Node clone = node.copyNode();
                afe.set(index(afe, node), clone);
                oe.set(index(oe, node), clone);
                node = clone;
                if (lastNode == furthestBlock) {
                    bookmark = index(afe, node) + 1;
                }
                if (lastNode.parent != null) {
                    lastNode.parent.removeChild(lastNode);
                }
                node.appendChild(lastNode);
                lastNode = node;
            }
            if (lastNode.parent != null) {
                lastNode.parent.removeChild(lastNode);
            }
            if (is(commonAncestor, "table", "tbody", "tfoot", "thead", "tr")) {
                fosterParent(lastNode);
            } else {
                commonAncestor.appendChild(lastNode);
            }
            Node clone = formattingElement.copyNode();
            while (furthestBlock.firstChild != null) {
                Node child = furthestBlock.firstChild;
                furthestBlock.removeChild(child);
                clone.appendChild(child);
            }
            furthestBlock.appendChild(clone);
            int oldLoc = index(afe, formattingElement);
            if (oldLoc != -1 && oldLoc < bookmark) {
                bookmark--;
            }
            remove(afe, formattingElement);
            afe.add(bookmark, clone);
            remove(oe, formattingElement);
            oe.add(index(oe, furthestBlock) + 1, clone);
        }
    }

    private void inBodyEndTagOther(String tagAtom, String tagName) {
        for (int i = oe.size() - 1; i >= 0; i--) {
            Node n = oe.get(i);
            if (n.namespace.isEmpty() && n.atom.equals(tagAtom) && n.data.equals(tagName)) {
                truncate(oe, i);
                break;
            }
            if (isSpecialElement(n)) {
                break;
            }
        }
    }

    private boolean textIM() {
        switch (tok.type) {
            case ERROR -> pop(oe);
            case TEXT -> {
                String d = tok.data;
                Node n = top(oe);
                if (n.atom.equals("textarea") && n.firstChild == null) {
                    if (!d.isEmpty() && d.charAt(0) == '\r') {
                        d = d.substring(1);
                    }
                    if (!d.isEmpty() && d.charAt(0) == '\n') {
                        d = d.substring(1);
                    }
                }
                if (d.isEmpty()) {
                    return true;
                }
                addText(d);
                return true;
            }
            case END_TAG -> pop(oe);
            default -> {
            }
        }
        im = originalIM;
        originalIM = null;
        return tok.type == TokenType.END_TAG;
    }

    private boolean inTableIM() {
        switch (tok.type) {
            case TEXT -> {
                tok.data = tok.data.replace("\0", "");
                if (is(top(oe), "table", "tbody", "tfoot", "thead", "tr")
                        && GoStrings.trim(tok.data, WHITESPACE).isEmpty()) {
                    addText(tok.data);
                    return true;
                }
            }
            case START_TAG -> {
                switch (tok.atom) {
                    case "caption" -> {
                        clearStackToContext(TABLE_SCOPE);
                        afe.add(SCOPE_MARKER);
                        addElement();
                        im = inCaptionIM;
                        return true;
                    }
                    case "colgroup" -> {
                        clearStackToContext(TABLE_SCOPE);
                        addElement();
                        im = inColumnGroupIM;
                        return true;
                    }
                    case "col" -> {
                        parseImpliedToken(TokenType.START_TAG, "colgroup");
                        return false;
                    }
                    case "tbody", "tfoot", "thead" -> {
                        clearStackToContext(TABLE_SCOPE);
                        addElement();
                        im = inTableBodyIM;
                        return true;
                    }
                    case "td", "th", "tr" -> {
                        parseImpliedToken(TokenType.START_TAG, "tbody");
                        return false;
                    }
                    case "table" -> {
                        if (popUntil(TABLE_SCOPE, "table")) {
                            resetInsertionMode();
                            return false;
                        }
                        return true;
                    }
                    case "style", "script", "template" -> {
                        return inHeadIM();
                    }
                    case "input" -> {
                        if (tok.attr != null) {
                            for (Node.Attribute t : tok.attr) {
                                if (t.key.equals("type") && GoStrings.equalFold(t.val, "hidden")) {
                                    addElement();
                                    pop(oe);
                                    return true;
                                }
                            }
                        }
                    }
                    case "form" -> {
                        if (contains(oe, "template") || form != null) {
                            return true;
                        }
                        addElement();
                        form = pop(oe);
                    }
                    default -> {
                    }
                }
            }
            case END_TAG -> {
                switch (tok.atom) {
                    case "table" -> {
                        if (popUntil(TABLE_SCOPE, "table")) {
                            resetInsertionMode();
                        }
                        return true;
                    }
                    case "body", "caption", "col", "colgroup", "html", "tbody", "td", "tfoot", "th", "thead", "tr" -> {
                        return true;
                    }
                    case "template" -> {
                        return inHeadIM();
                    }
                    default -> {
                    }
                }
            }
            case COMMENT -> {
                addChild(Node.comment(tok.data));
                return true;
            }
            case DOCTYPE -> {
                return true;
            }
            case ERROR -> {
                return inBodyIM();
            }
            default -> {
            }
        }
        fosterParenting = true;
        try {
            return inBodyIM();
        } finally {
            fosterParenting = false;
        }
    }

    private boolean inCaptionIM() {
        switch (tok.type) {
            case START_TAG -> {
                if (tokIs("caption", "col", "colgroup", "tbody", "td", "tfoot", "thead", "tr")) {
                    if (!popUntil(TABLE_SCOPE, "caption")) {
                        return true;
                    }
                    clearActiveFormattingElements();
                    im = inTableIM;
                    return false;
                }
            }
            case END_TAG -> {
                switch (tok.atom) {
                    case "caption" -> {
                        if (popUntil(TABLE_SCOPE, "caption")) {
                            clearActiveFormattingElements();
                            im = inTableIM;
                        }
                        return true;
                    }
                    case "table" -> {
                        if (!popUntil(TABLE_SCOPE, "caption")) {
                            return true;
                        }
                        clearActiveFormattingElements();
                        im = inTableIM;
                        return false;
                    }
                    case "body", "col", "colgroup", "html", "tbody", "td", "tfoot", "th", "thead", "tr" -> {
                        return true;
                    }
                    default -> {
                    }
                }
            }
            default -> {
            }
        }
        return inBodyIM();
    }

    private boolean inColumnGroupIM() {
        switch (tok.type) {
            case TEXT -> {
                String s = GoStrings.trimLeft(tok.data, WHITESPACE);
                if (s.length() < tok.data.length()) {
                    addText(tok.data.substring(0, tok.data.length() - s.length()));
                    if (s.isEmpty()) {
                        return true;
                    }
                    tok.data = s;
                }
            }
            case COMMENT -> {
                addChild(Node.comment(tok.data));
                return true;
            }
            case DOCTYPE -> {
                return true;
            }
            case START_TAG -> {
                switch (tok.atom) {
                    case "html" -> {
                        return inBodyIM();
                    }
                    case "col" -> {
                        addElement();
                        pop(oe);
                        acknowledgeSelfClosingTag();
                        return true;
                    }
                    case "template" -> {
                        return inHeadIM();
                    }
                    default -> {
                    }
                }
            }
            case END_TAG -> {
                switch (tok.atom) {
                    case "colgroup" -> {
                        if (top(oe).atom.equals("colgroup")) {
                            pop(oe);
                            im = inTableIM;
                        }
                        return true;
                    }
                    case "col" -> {
                        return true;
                    }
                    case "template" -> {
                        return inHeadIM();
                    }
                    default -> {
                    }
                }
            }
            case ERROR -> {
                return inBodyIM();
            }
            default -> {
            }
        }
        if (!top(oe).atom.equals("colgroup")) {
            return true;
        }
        pop(oe);
        im = inTableIM;
        return false;
    }

    private boolean inTableBodyIM() {
        switch (tok.type) {
            case START_TAG -> {
                switch (tok.atom) {
                    case "tr" -> {
                        clearStackToContext(TABLE_BODY_SCOPE);
                        addElement();
                        im = inRowIM;
                        return true;
                    }
                    case "td", "th" -> {
                        parseImpliedToken(TokenType.START_TAG, "tr");
                        return false;
                    }
                    case "caption", "col", "colgroup", "tbody", "tfoot", "thead" -> {
                        if (popUntil(TABLE_SCOPE, "tbody", "thead", "tfoot")) {
                            im = inTableIM;
                            return false;
                        }
                        return true;
                    }
                    default -> {
                    }
                }
            }
            case END_TAG -> {
                switch (tok.atom) {
                    case "tbody", "tfoot", "thead" -> {
                        if (elementInScope(TABLE_SCOPE, tok.atom)) {
                            clearStackToContext(TABLE_BODY_SCOPE);
                            pop(oe);
                            im = inTableIM;
                        }
                        return true;
                    }
                    case "table" -> {
                        if (popUntil(TABLE_SCOPE, "tbody", "thead", "tfoot")) {
                            im = inTableIM;
                            return false;
                        }
                        return true;
                    }
                    case "body", "caption", "col", "colgroup", "html", "td", "th", "tr" -> {
                        return true;
                    }
                    default -> {
                    }
                }
            }
            case COMMENT -> {
                addChild(Node.comment(tok.data));
                return true;
            }
            default -> {
            }
        }
        return inTableIM();
    }

    private boolean inRowIM() {
        switch (tok.type) {
            case START_TAG -> {
                switch (tok.atom) {
                    case "td", "th" -> {
                        clearStackToContext(TABLE_ROW_SCOPE);
                        addElement();
                        afe.add(SCOPE_MARKER);
                        im = inCellIM;
                        return true;
                    }
                    case "caption", "col", "colgroup", "tbody", "tfoot", "thead", "tr" -> {
                        if (elementInScope(TABLE_SCOPE, "tr")) {
                            clearStackToContext(TABLE_ROW_SCOPE);
                            pop(oe);
                            im = inTableBodyIM;
                            return false;
                        }
                        return true;
                    }
                    default -> {
                    }
                }
            }
            case END_TAG -> {
                switch (tok.atom) {
                    case "tr" -> {
                        if (elementInScope(TABLE_SCOPE, "tr")) {
                            clearStackToContext(TABLE_ROW_SCOPE);
                            pop(oe);
                            im = inTableBodyIM;
                        }
                        return true;
                    }
                    case "table" -> {
                        if (elementInScope(TABLE_SCOPE, "tr")) {
                            clearStackToContext(TABLE_ROW_SCOPE);
                            pop(oe);
                            im = inTableBodyIM;
                            return false;
                        }
                        return true;
                    }
                    case "tbody", "tfoot", "thead" -> {
                        if (elementInScope(TABLE_SCOPE, tok.atom) && elementInScope(TABLE_SCOPE, "tr")) {
                            clearStackToContext(TABLE_ROW_SCOPE);
                            pop(oe);
                            im = inTableBodyIM;
                            return false;
                        }
                        return true;
                    }
                    case "body", "caption", "col", "colgroup", "html", "td", "th" -> {
                        return true;
                    }
                    default -> {
                    }
                }
            }
            default -> {
            }
        }
        return inTableIM();
    }

    private boolean inCellIM() {
        switch (tok.type) {
            case START_TAG -> {
                if (tokIs("caption", "col", "colgroup", "tbody", "td", "tfoot", "th", "thead", "tr")) {
                    if (popUntil(TABLE_SCOPE, "td", "th")) {
                        clearActiveFormattingElements();
                        im = inRowIM;
                        return false;
                    }
                    return true;
                }
            }
            case END_TAG -> {
                switch (tok.atom) {
                    case "td", "th" -> {
                        if (!popUntil(TABLE_SCOPE, tok.atom)) {
                            return true;
                        }
                        clearActiveFormattingElements();
                        im = inRowIM;
                        return true;
                    }
                    case "body", "caption", "col", "colgroup", "html" -> {
                        return true;
                    }
                    case "table", "tbody", "tfoot", "thead", "tr" -> {
                        if (!elementInScope(TABLE_SCOPE, tok.atom)) {
                            return true;
                        }
                        if (popUntil(TABLE_SCOPE, "td", "th")) {
                            clearActiveFormattingElements();
                        }
                        im = inRowIM;
                        return false;
                    }
                    default -> {
                    }
                }
            }
            default -> {
            }
        }
        return inBodyIM();
    }

    private void replaceTemplateMode(InsertionMode mode) {
        templateStack.remove(templateStack.size() - 1);
        templateStack.add(mode);
        im = mode;
    }

    private boolean inTemplateIM() {
        switch (tok.type) {
            case TEXT, COMMENT, DOCTYPE -> {
                return inBodyIM();
            }
            case START_TAG -> {
                switch (tok.atom) {
                    case "base", "basefont", "bgsound", "link", "meta", "noframes", "script", "style", "template",
                            "title" -> {
                        return inHeadIM();
                    }
                    case "caption", "colgroup", "tbody", "tfoot", "thead" -> replaceTemplateMode(inTableIM);
                    case "col" -> replaceTemplateMode(inColumnGroupIM);
                    case "tr" -> replaceTemplateMode(inTableBodyIM);
                    case "td", "th" -> replaceTemplateMode(inRowIM);
                    default -> replaceTemplateMode(inBodyIM);
                }
                return false;
            }
            case END_TAG -> {
                if (tok.atom.equals("template")) {
                    return inHeadIM();
                }
                return true;
            }
            case ERROR -> {
                if (!contains(oe, "template")) {
                    return true;
                }
                generateImpliedEndTags();
                for (int i = oe.size() - 1; i >= 0; i--) {
                    Node n = oe.get(i);
                    if (n.namespace.isEmpty() && n.atom.equals("template")) {
                        truncate(oe, i);
                        break;
                    }
                }
                clearActiveFormattingElements();
                templateStack.remove(templateStack.size() - 1);
                resetInsertionMode();
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    private boolean afterBodyIM() {
        switch (tok.type) {
            case ERROR -> {
                return true;
            }
            case TEXT -> {
                if (GoStrings.trimLeft(tok.data, WHITESPACE).isEmpty()) {
                    return inBodyIM();
                }
            }
            case START_TAG -> {
                if (tok.atom.equals("html")) {
                    return inBodyIM();
                }
            }
            case END_TAG -> {
                if (tok.atom.equals("html")) {
                    im = afterAfterBodyIM;
                    return true;
                }
            }
            case COMMENT -> {
                if (oe.isEmpty() || !oe.get(0).atom.equals("html")) {
                    throw new ParseException("html: bad parser state: <html> element not found, in the after-body insertion mode");
                }
                addComment(oe.get(0));
                return true;
            }
            default -> {
            }
        }
        im = inBodyIM;
        return false;
    }

    private static String keepWhitespace(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\f' || c == '\r') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private boolean inFramesetIM() {
        switch (tok.type) {
            case COMMENT -> addChild(Node.comment(tok.data));
            case TEXT -> {
                String s = keepWhitespace(tok.data);
                if (!s.isEmpty()) {
                    addText(s);
                }
            }
            case START_TAG -> {
                switch (tok.atom) {
                    case "html" -> {
                        return inBodyIM();
                    }
                    case "frameset" -> addElement();
                    case "frame" -> {
                        addElement();
                        pop(oe);
                        acknowledgeSelfClosingTag();
                    }
                    case "noframes" -> {
                        return inHeadIM();
                    }
                    default -> {
                    }
                }
            }
            case END_TAG -> {
                if (tok.atom.equals("frameset") && !top(oe).atom.equals("html")) {
                    pop(oe);
                    if (!top(oe).atom.equals("frameset")) {
                        im = afterFramesetIM;
                        return true;
                    }
                }
            }
            default -> {
            }
        }
        return true;
    }

    private boolean afterFramesetIM() {
        switch (tok.type) {
            case COMMENT -> addChild(Node.comment(tok.data));
            case TEXT -> {
                String s = keepWhitespace(tok.data);
                if (!s.isEmpty()) {
                    addText(s);
                }
            }
            case START_TAG -> {
                if (tok.atom.equals("html")) {
                    return inBodyIM();
                } else if (tok.atom.equals("noframes")) {
                    return inHeadIM();
                }
            }
            case END_TAG -> {
                if (tok.atom.equals("html")) {
                    im = afterAfterFramesetIM;
                    return true;
                }
            }
            default -> {
            }
        }
        return true;
    }

    private boolean afterAfterBodyIM() {
        switch (tok.type) {
            case ERROR -> {
                return true;
            }
            case TEXT -> {
                if (GoStrings.trimLeft(tok.data, WHITESPACE).isEmpty()) {
                    return inBodyIM();
                }
            }
            case START_TAG -> {
                if (tok.atom.equals("html")) {
                    return inBodyIM();
                }
            }
            case COMMENT -> {
                addComment(doc);
                return true;
            }
            case DOCTYPE -> {
                return inBodyIM();
            }
            default -> {
            }
        }
        im = inBodyIM;
        return false;
    }

    private boolean afterAfterFramesetIM() {
        switch (tok.type) {
            case COMMENT -> addComment(doc);
            case TEXT -> {
                String s = keepWhitespace(tok.data);
                if (!s.isEmpty()) {
                    tok.data = s;
                    return inBodyIM();
                }
            }
            case START_TAG -> {
                if (tok.atom.equals("html")) {
                    return inBodyIM();
                } else if (tok.atom.equals("noframes")) {
                    return inHeadIM();
                }
            }
            case DOCTYPE -> {
                return inBodyIM();
            }
            default -> {
            }
        }
        return true;
    }

    // ---- foreign content ----

    private static void adjustAttributeNames(List<Node.Attribute> aa, Map<String, String> nameMap) {
        if (aa == null) {
            return;
        }
        for (Node.Attribute a : aa) {
            String newName = nameMap.get(a.key);
            if (newName != null) {
                a.key = newName;
            }
        }
    }

    private static void adjustForeignAttributes(List<Node.Attribute> aa) {
        if (aa == null) {
            return;
        }
        for (Node.Attribute a : aa) {
            if (FOREIGN_ATTRIBUTES.contains(a.key)) {
                int j = a.key.indexOf(':');
                a.namespace = a.key.substring(0, j);
                a.key = a.key.substring(j + 1);
            }
        }
    }

    private static boolean htmlIntegrationPoint(Node n) {
        if (n.type != Node.Type.ELEMENT) {
            return false;
        }
        switch (n.namespace) {
            case "math" -> {
                if (n.data.equals("annotation-xml")) {
                    for (Node.Attribute a : n.attr) {
                        if (a.key.equals("encoding") && (GoStrings.equalFold(a.val, "text/html")
                                || GoStrings.equalFold(a.val, "application/xhtml+xml"))) {
                            return true;
                        }
                    }
                }
            }
            case "svg" -> {
                return n.data.equals("desc") || n.data.equals("foreignObject") || n.data.equals("title");
            }
            default -> {
            }
        }
        return false;
    }

    private static boolean mathMLTextIntegrationPoint(Node n) {
        if (!n.namespace.equals("math")) {
            return false;
        }
        return MATH_TEXT_INTEGRATION.contains(n.data);
    }

    private boolean parseForeignContent() {
        switch (tok.type) {
            case TEXT -> {
                if (framesetOK) {
                    framesetOK = GoStrings.trimLeft(tok.data, WHITESPACE + "\0").isEmpty();
                }
                tok.data = tok.data.replace("\0", Tokenizer.REPLACEMENT);
                addText(tok.data);
            }
            case COMMENT -> addChild(Node.comment(tok.data));
            case START_TAG -> {
                boolean b = BREAKOUT.contains(tok.data);
                if (tok.atom.equals("font") && tok.attr != null) {
                    for (Node.Attribute attr : tok.attr) {
                        if (attr.key.equals("color") || attr.key.equals("face") || attr.key.equals("size")) {
                            b = true;
                            break;
                        }
                    }
                }
                if (b) {
                    for (int i = oe.size() - 1; i >= 0; i--) {
                        Node n = oe.get(i);
                        if (n.namespace.isEmpty() || htmlIntegrationPoint(n) || mathMLTextIntegrationPoint(n)) {
                            truncate(oe, i + 1);
                            break;
                        }
                    }
                    return im.apply();
                }
                Node current = adjustedCurrentNode();
                switch (current.namespace) {
                    case "math" -> adjustAttributeNames(tok.attr, MATHML_ATTRIBUTE_ADJUSTMENTS);
                    case "svg" -> {
                        String x = SVG_TAG_NAME_ADJUSTMENTS.get(tok.data);
                        if (x != null) {
                            tok.atom = x;
                            tok.data = x;
                        }
                        adjustAttributeNames(tok.attr, SVG_ATTRIBUTE_ADJUSTMENTS);
                    }
                    default -> throw new ParseException("html: bad parser state: unexpected namespace");
                }
                adjustForeignAttributes(tok.attr);
                String namespace = current.namespace;
                addElement();
                top().namespace = namespace;
                if (!namespace.isEmpty()) {
                    tokenizer.nextIsNotRawText();
                }
                if (hasSelfClosingToken) {
                    pop(oe);
                    acknowledgeSelfClosingTag();
                }
            }
            case END_TAG -> {
                if (GoStrings.equalFold(oe.get(oe.size() - 1).data, tok.data)) {
                    truncate(oe, oe.size() - 1);
                    return true;
                }
                for (int i = oe.size() - 1; i >= 0; i--) {
                    if (GoStrings.equalFold(oe.get(i).data, tok.data)) {
                        truncate(oe, i);
                        return true;
                    }
                    if (i > 0 && oe.get(i - 1).namespace.isEmpty()) {
                        break;
                    }
                }
                return im.apply();
            }
            default -> {
            }
        }
        return true;
    }

    private Node adjustedCurrentNode() {
        return top(oe);
    }

    private boolean inForeignContent() {
        if (oe.isEmpty()) {
            return false;
        }
        Node n = adjustedCurrentNode();
        if (n.namespace.isEmpty()) {
            return false;
        }
        if (mathMLTextIntegrationPoint(n)) {
            if (tok.type == TokenType.START_TAG && !tok.atom.equals("mglyph") && !tok.atom.equals("malignmark")) {
                return false;
            }
            if (tok.type == TokenType.TEXT) {
                return false;
            }
        }
        if (n.namespace.equals("math") && n.atom.equals("annotation-xml") && tok.type == TokenType.START_TAG
                && tok.atom.equals("svg")) {
            return false;
        }
        if (htmlIntegrationPoint(n) && (tok.type == TokenType.START_TAG || tok.type == TokenType.TEXT)) {
            return false;
        }
        return tok.type != TokenType.ERROR;
    }

    private void parseImpliedToken(TokenType t, String name) {
        Token realToken = tok;
        boolean selfClosing = hasSelfClosingToken;
        tok = new Token(t, name, name, null);
        hasSelfClosingToken = false;
        parseCurrentToken();
        tok = realToken;
        hasSelfClosingToken = selfClosing;
    }

    private void parseCurrentToken() {
        if (tok.type == TokenType.SELF_CLOSING_TAG) {
            hasSelfClosingToken = true;
            tok.type = TokenType.START_TAG;
        }
        boolean consumed = false;
        while (!consumed) {
            if (inForeignContent()) {
                consumed = parseForeignContent();
            } else {
                consumed = im.apply();
            }
        }
        hasSelfClosingToken = false;
    }

    // ---- doctype ----

    private static int indexAny(String s, String chars) {
        for (int i = 0; i < s.length(); i++) {
            if (chars.indexOf(s.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    private Node parseDoctype(String s) {
        Node n = new Node(Node.Type.DOCTYPE, "", "", null);
        boolean q = false;
        int space = indexAny(s, WHITESPACE);
        if (space == -1) {
            space = s.length();
        }
        n.data = s.substring(0, space);
        if (!n.data.equals("html")) {
            q = true;
        }
        n.data = GoStrings.toLower(n.data);
        s = GoStrings.trimLeft(s.substring(space), WHITESPACE);
        if (s.length() < 6) {
            quirks = q || !s.isEmpty();
            return n;
        }
        String key = GoStrings.toLower(s.substring(0, 6));
        s = s.substring(6);
        while (key.equals("public") || key.equals("system")) {
            s = GoStrings.trimLeft(s, WHITESPACE);
            if (s.isEmpty()) {
                break;
            }
            char quote = s.charAt(0);
            if (quote != '"' && quote != '\'') {
                break;
            }
            s = s.substring(1);
            int qi = s.indexOf(quote);
            String id;
            if (qi == -1) {
                id = s;
                s = "";
            } else {
                id = s.substring(0, qi);
                s = s.substring(qi + 1);
            }
            n.attr.add(new Node.Attribute("", key, id));
            key = key.equals("public") ? "system" : "";
        }
        if (!key.isEmpty() || !s.isEmpty()) {
            q = true;
        } else if (!n.attr.isEmpty()) {
            if (n.attr.get(0).key.equals("public")) {
                String pub = GoStrings.toLower(n.attr.get(0).val);
                switch (pub) {
                    case "-//w3o//dtd w3 html strict 3.0//en//", "-/w3d/dtd html 4.0 transitional/en", "html" -> q = true;
                    default -> {
                        for (String id : QUIRKY_IDS) {
                            if (pub.startsWith(id)) {
                                q = true;
                                break;
                            }
                        }
                    }
                }
                if (n.attr.size() == 1 && (pub.startsWith("-//w3c//dtd html 4.01 frameset//")
                        || pub.startsWith("-//w3c//dtd html 4.01 transitional//"))) {
                    q = true;
                }
            }
            Node.Attribute lastAttr = n.attr.get(n.attr.size() - 1);
            if (lastAttr.key.equals("system")
                    && GoStrings.equalFold(lastAttr.val, "http://www.ibm.com/data/dtd/v11/ibmxhtml1-transitional.dtd")) {
                q = true;
            }
        }
        quirks = q;
        return n;
    }
}
