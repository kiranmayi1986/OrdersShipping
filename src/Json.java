import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader (objects, arrays, strings, numbers, booleans, null) so the
 * project has zero external dependencies. Numbers are always returned as Double.
 */
final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        Json p = new Json(text);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (p.i != p.s.length()) throw p.error("unexpected trailing content");
        return v;
    }

    // ---- typed accessors used by the loader (they produce readable errors) ----

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object o, String where) {
        if (!(o instanceof Map)) throw new IllegalArgumentException(where + ": expected a JSON object");
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o, String where) {
        if (!(o instanceof List)) throw new IllegalArgumentException(where + ": expected a JSON array");
        return (List<Object>) o;
    }

    static String str(Object o, String where) {
        if (!(o instanceof String)) throw new IllegalArgumentException(where + ": expected a string");
        return (String) o;
    }

    static double num(Object o, String where) {
        if (!(o instanceof Double)) throw new IllegalArgumentException(where + ": expected a number");
        return (Double) o;
    }

    static int nonNegInt(Object o, String where) {
        double d = num(o, where);
        if (d != Math.rint(d) || d < 0 || d > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(where + ": expected a non-negative integer, got " + d);
        }
        return (int) d;
    }

    // ---- parser ----

    private Object value() {
        if (i >= s.length()) throw error("unexpected end of input");
        switch (s.charAt(i)) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': return literal("true", Boolean.TRUE);
            case 'f': return literal("false", Boolean.FALSE);
            case 'n': return literal("null", null);
            default:  return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        skipWs();
        if (peek('}')) { i++; return m; }
        while (true) {
            skipWs();
            if (!peek('"')) throw error("expected a string key");
            String k = string();
            skipWs();
            expect(':');
            skipWs();
            m.put(k, value());
            skipWs();
            if (peek(',')) { i++; continue; }
            expect('}');
            return m;
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++;
        skipWs();
        if (peek(']')) { i++; return l; }
        while (true) {
            skipWs();
            l.add(value());
            skipWs();
            if (peek(',')) { i++; continue; }
            expect(']');
            return l;
        }
    }

    private String string() {
        StringBuilder sb = new StringBuilder();
        i++; // opening quote
        while (true) {
            if (i >= s.length()) throw error("unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            if (i >= s.length()) throw error("bad escape");
            char e = s.charAt(i++);
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) throw error("bad unicode escape");
                    sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default: throw error("bad escape \\" + e);
            }
        }
    }

    private Double number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (start == i) throw error("unexpected character '" + s.charAt(i) + "'");
        try {
            return Double.parseDouble(s.substring(start, i));
        } catch (NumberFormatException ex) {
            i = start;
            throw error("bad number");
        }
    }

    private Object literal(String word, Object result) {
        if (!s.startsWith(word, i)) throw error("unexpected token");
        i += word.length();
        return result;
    }

    private void skipWs() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private boolean peek(char c) {
        return i < s.length() && s.charAt(i) == c;
    }

    private void expect(char c) {
        if (!peek(c)) throw error("expected '" + c + "'");
        i++;
    }

    private IllegalArgumentException error(String msg) {
        return new IllegalArgumentException("JSON parse error at offset " + i + ": " + msg);
    }
}
