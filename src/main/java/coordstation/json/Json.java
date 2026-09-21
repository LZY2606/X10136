package coordstation.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Minimal JSON parser and deterministic serializer (no external deps). */
public final class Json {

    private Json() {}

    // ---------- parsing ----------

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.parseValue();
        p.skipWs();
        if (!p.atEnd()) throw new JsonException("trailing content at offset " + p.pos);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new JsonException("expected JSON object");
        return (Map<String, Object>) v;
    }

    public static final class JsonException extends RuntimeException {
        public JsonException(String msg) { super(msg); }
    }

    private static final class Parser {
        final String s;
        int pos;
        Parser(String s) { this.s = s; }
        boolean atEnd() { return pos >= s.length(); }
        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++; else break;
            }
        }
        char peek() {
            if (pos >= s.length()) throw new JsonException("unexpected end of input");
            return s.charAt(pos);
        }
        void expect(char c) {
            if (atEnd() || s.charAt(pos) != c)
                throw new JsonException("expected '" + c + "' at offset " + pos);
            pos++;
        }
        Object parseValue() {
            skipWs();
            char c = peek();
            switch (c) {
                case '{': return parseObject();
                case '[': return parseArray();
                case '"': return parseString();
                case 't': expectWord("true"); return Boolean.TRUE;
                case 'f': expectWord("false"); return Boolean.FALSE;
                case 'n': expectWord("null"); return null;
                default: return parseNumber();
            }
        }
        void expectWord(String w) {
            if (!s.startsWith(w, pos)) throw new JsonException("bad literal at offset " + pos);
            pos += w.length();
        }
        Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> m = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') { pos++; return m; }
            while (true) {
                skipWs();
                String k = parseString();
                skipWs();
                expect(':');
                Object v = parseValue();
                m.put(k, v);
                skipWs();
                char c = peek();
                if (c == ',') { pos++; continue; }
                if (c == '}') { pos++; return m; }
                throw new JsonException("expected ',' or '}' at offset " + pos);
            }
        }
        List<Object> parseArray() {
            expect('[');
            List<Object> l = new ArrayList<>();
            skipWs();
            if (peek() == ']') { pos++; return l; }
            while (true) {
                l.add(parseValue());
                skipWs();
                char c = peek();
                if (c == ',') { pos++; continue; }
                if (c == ']') { pos++; return l; }
                throw new JsonException("expected ',' or ']' at offset " + pos);
            }
        }
        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) throw new JsonException("unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (atEnd()) throw new JsonException("bad escape at end");
                    char e = s.charAt(pos++);
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
                            if (pos + 4 > s.length()) throw new JsonException("bad \\u escape");
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default: throw new JsonException("bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }
        Number parseNumber() {
            int start = pos;
            if (!atEnd() && (s.charAt(pos) == '-' || s.charAt(pos) == '+')) pos++;
            boolean any = false;
            while (!atEnd() && Character.isDigit(s.charAt(pos))) { pos++; any = true; }
            boolean isDouble = false;
            if (!atEnd() && s.charAt(pos) == '.') {
                isDouble = true; pos++;
                while (!atEnd() && Character.isDigit(s.charAt(pos))) { pos++; any = true; }
            }
            if (!atEnd() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                isDouble = true; pos++;
                if (!atEnd() && (s.charAt(pos) == '-' || s.charAt(pos) == '+')) pos++;
                boolean expAny = false;
                while (!atEnd() && Character.isDigit(s.charAt(pos))) { pos++; expAny = true; }
                if (!expAny) throw new JsonException("bad exponent at offset " + start);
            }
            if (!any) throw new JsonException("bad number at offset " + start);
            String num = s.substring(start, pos);
            try {
                if (!isDouble) return Long.parseLong(num);
                return Double.parseDouble(num);
            } catch (NumberFormatException e) {
                // Overflowing integers / huge exponents: fall back to double (may be infinite,
                // which downstream validation rejects explicitly).
                try {
                    return Double.parseDouble(num);
                } catch (NumberFormatException e2) {
                    throw new JsonException("bad number '" + num + "'");
                }
            }
        }
    }

    // ---------- serialization ----------

    /** Canonical serialization: object keys sorted, doubles via Double.toString. */
    public static String writeCanonical(Object v) {
        StringBuilder sb = new StringBuilder();
        write(v, sb, true, -1, 0);
        return sb.toString();
    }

    public static String writePretty(Object v) {
        StringBuilder sb = new StringBuilder();
        write(v, sb, false, 2, 0);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(Object v, StringBuilder sb, boolean canonical, int indent, int level) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof String) { writeString((String) v, sb); return; }
        if (v instanceof Boolean) { sb.append(v); return; }
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d))
                throw new JsonException("non-finite number cannot be serialized: " + d);
            sb.append(Double.toString(d));
            return;
        }
        if (v instanceof Number) { sb.append(v.toString()); return; }
        if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            if (canonical && !(m instanceof TreeMap)) m = new TreeMap<>(m);
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                newline(sb, indent, level + 1);
                writeString(e.getKey(), sb);
                sb.append(':');
                if (indent > 0) sb.append(' ');
                write(e.getValue(), sb, canonical, indent, level + 1);
            }
            if (!m.isEmpty()) newline(sb, indent, level);
            sb.append('}');
            return;
        }
        if (v instanceof Iterable) {
            Iterable<?> it = (Iterable<?>) v;
            sb.append('[');
            boolean first = true;
            boolean any = false;
            for (Object o : it) {
                any = true;
                if (!first) sb.append(',');
                first = false;
                newline(sb, indent, level + 1);
                write(o, sb, canonical, indent, level + 1);
            }
            if (any) newline(sb, indent, level);
            sb.append(']');
            return;
        }
        throw new JsonException("cannot serialize " + v.getClass());
    }

    private static void newline(StringBuilder sb, int indent, int level) {
        if (indent <= 0) return;
        sb.append('\n');
        for (int i = 0; i < indent * level; i++) sb.append(' ');
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    // ---------- typed accessors ----------

    public static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof String)) throw new JsonException("missing string field '" + key + "'");
        return (String) v;
    }

    public static String strOr(Map<String, Object> m, String key, String dflt) {
        Object v = m.get(key);
        return v instanceof String ? (String) v : dflt;
    }

    public static double num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof Number)) throw new JsonException("missing numeric field '" + key + "'");
        return ((Number) v).doubleValue();
    }

    public static boolean bool(Map<String, Object> m, String key, boolean dflt) {
        Object v = m.get(key);
        return v instanceof Boolean ? (Boolean) v : dflt;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof List)) throw new JsonException("missing array field '" + key + "'");
        return (List<Object>) v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof Map)) throw new JsonException("missing object field '" + key + "'");
        return (Map<String, Object>) v;
    }
}
