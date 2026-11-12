package io.github.ffuf.pkg.goquery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Expected values come from goquery v1.13.0 and cascadia v1.3.5 run on the same input. */
class SelectorTest {
    private static final String PAGE = "<!DOCTYPE html><html lang=\"en-US\"><head><title>T &amp; x</title>"
            + "<meta name=\"csrf-token\" content=\"tok\"></head><body id=\"top\" class=\"home page\">"
            + "<ul id=\"list\"><li class=\"item first\">One</li><li class=\"item\">Two <span>2</span></li>"
            + "<li class=\"item last\">Three</li><li>  </li></ul>"
            + "<p>Para 1</p><p>Para <b>2</b></p><a href=\"/about\" data-x=\"a b\">About</a><a>none</a>"
            + "<form><input type=\"radio\" name=\"r\" checked><input name=\"d\" disabled>"
            + "<select><option>1</option><option selected>2</option></select></form>"
            + "<div lang=\"fr\"><span>fr</span></div></body></html>";

    private static List<String> texts(String html, String selector) {
        Selection doc = Document.parse(html.getBytes(StandardCharsets.UTF_8));
        Selection found = doc.find(selector);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < found.length(); i++) {
            out.add(found.eq(i).text());
        }
        return out;
    }

    private static List<String> texts(String selector) {
        return texts(PAGE, selector);
    }

    @Test
    void typeClassIdAndUniversal() {
        assertEquals(List.of("One", "Two 2", "Three", "  "), texts("li"));
        assertEquals(List.of("One", "Two 2", "Three", "  "), texts("LI"));
        assertEquals(List.of("Three"), texts("li.item.last"));
        assertEquals(List.of("One"), texts("#list > li:first-child"));
        assertEquals(24, texts("*").size());
    }

    @Test
    void attributeOperators() {
        assertEquals(List.of("About"), texts("a[href]"));
        assertEquals(List.of("none"), texts("a:not([href])"));
        assertEquals(List.of("About"), texts("[data-x~=b]"));
        assertEquals(List.of("About"), texts("[href^=\"/\"]"));
        assertEquals(List.of("About"), texts("[href$=out]"));
        assertEquals(List.of("About"), texts("[href*=bou]"));
        assertEquals(1, texts("[lang|=en]").size());
        assertEquals(List.of(""), texts("meta[name=\"CSRF-TOKEN\" i]"));
        assertEquals(List.of("About"), texts("[href#=^/a]"));
        assertEquals(List.of("none"), texts("a[href!=\"/about\"]"));
    }

    @Test
    void structuralPseudoClasses() {
        assertEquals(List.of("One", "Three"), texts("li:nth-child(odd)"));
        assertEquals(List.of("Two 2", "  "), texts("li:nth-child(2n)"));
        assertEquals(List.of("One", "Three"), texts("li:nth-child(2n - 1)"));
        assertEquals(List.of("One", "Two 2"), texts("li:nth-child(-n+2)"));
        assertEquals(List.of("  "), texts("li:nth-last-child(1)"));
        assertEquals(List.of("Three"), texts("li:nth-of-type(3)"));
        assertEquals(List.of("2", "fr"), texts("span:only-child"));
        assertEquals(List.of("  "), texts("li:empty"));
        assertEquals(1, texts(":root").size());
    }

    @Test
    void textPseudoClassesAlsoMatchTextNodes() {
        assertEquals(List.of("Para 1", "Para 2"), texts("p:contains(PARA)"));
        assertEquals(List.of("Para 1", "Para 2"), texts("p:matches(^Para \\d$)"));
        assertEquals(List.of("Para 1"), texts("p:matchesOwn(^Para \\d$)"));
        assertEquals(List.of("Para 1", "Para 1", "Para 2", "Para "), texts("body :contains(para)"));
        assertEquals(List.of("Para 2"), texts("p:has(b)"));
    }

    @Test
    void formPseudoClasses() {
        assertEquals(List.of("", "2"), texts(":checked"));
        assertEquals(List.of(""), texts("input:disabled"));
        assertEquals(List.of("About"), texts(":link"));
        assertEquals(List.of("", "", "12"), texts(":input"));
        assertEquals(List.of("fr"), texts(":lang(fr) span"));
    }

    @Test
    void combinatorsAndGroupsKeepDocumentOrder() {
        assertEquals(List.of("Two 2", "Three", "  "), texts("li + li"));
        assertEquals(List.of("Para 2"), texts("p ~ p"));
        assertEquals(List.of("T & x", "One"), texts("li.first, title"));
    }

    @Test
    void invalidSelectorsMatchNothing() {
        for (String sel : List.of("p,", ",p", "p >", "[name", "div)", "p::before", "p:after", ":selected",
                "div:has(> span)", "*|*", "", ":unknown")) {
            assertTrue(texts(sel).isEmpty(), sel);
        }
        assertEquals(List.of("Para 1", "Para 2"), texts("*|* p"));
    }

    @Test
    void escapesInIdentifiers() {
        assertEquals(List.of("x"), texts("<p id=\"123\">x</p>", "#\\31 23"));
        assertEquals(List.of("y"), texts("<p class=\"\u00fcber\">y</p>", ".\\00fc ber"));
        assertEquals(List.of("z"), texts("<ns:tag>z</ns:tag>", "ns\\:tag"));
    }

    @Test
    void foreignContentKeepsAdjustedCase() {
        String svg = "<svg viewbox=\"0 0 1 1\"><clippath id=\"c\"></clippath><foreignobject><p>in</p></foreignobject></svg>";
        assertTrue(texts(svg, "clippath").isEmpty());
        assertTrue(texts(svg, "foreignObject p").isEmpty());
        assertEquals(List.of("in"), texts(svg, "svg p"));
        assertEquals("clipPath", Document.parse(svg.getBytes(StandardCharsets.UTF_8)).find("[id=c]").nodeName());
    }

    @Test
    void parserTreeShapes() {
        assertEquals(List.of("straya"), texts("<table>stray<tr><td>a</td></tr></table>", "body:containsOwn(stray)"));
        assertEquals(List.of("a"), texts("<table>stray<tr><td>a</td></tr></table>", "table td"));
        assertEquals(List.of("1", "2"), texts("<p>1<p>2", "p"));
        assertEquals(List.of("x &amp; <b>y</b>"), texts("<textarea>\nx &amp;amp; <b>y</b></textarea>", "textarea"));
        assertEquals(List.of("var a = '</p>';"), texts("<script>var a = '</p>';</script>", "script"));
        assertEquals(List.of("<p>raw</p>"), texts("<noscript><p>raw</p></noscript>", "noscript"));
        assertEquals(List.of("in template"), texts("<template><td>in template</td></template>", "template td"));
        assertEquals(List.of("a \u00acit; \u00a9 A\u20ac\ufffd"), texts("<p>a &notit; &copy A&#128;&#0;</p>", "p"));
        assertEquals(List.of("x"), texts("<div class=one class=two>x</div>", "div.one"));
        assertTrue(texts("<div class=one class=two>x</div>", ".two").isEmpty());
    }

    @Test
    void tooManyOpenElementsIsAParseError() {
        String deep = "<div>".repeat(600);
        assertNull(Document.parse(deep.getBytes(StandardCharsets.UTF_8)));
        assertTrue(Document.parse("<div>".repeat(500).getBytes(StandardCharsets.UTF_8)) != null);
    }

    @Test
    void attrAndNodeName() {
        Selection doc = Document.parse(PAGE.getBytes(StandardCharsets.UTF_8));
        Selection meta = doc.find("meta[name]").first();
        assertEquals("tok", meta.attrOr("content", ""));
        assertTrue(meta.attr("missing").isEmpty());
        assertEquals("meta", meta.nodeName());
        assertEquals("", doc.find("nothing").nodeName());
        assertEquals("#document", doc.nodeName());
    }
}
