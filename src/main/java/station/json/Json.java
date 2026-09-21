package station.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Minimal dependency-free JSON parser/serializer with deterministic output. */
public final class Json {
    private Json() {}

    public static final class JsonException extends RuntimeException {
        public JsonException(String message) { super(message); }
    }

    // ---------- parsing ----------

    public static Object parse(String text) {
        Parser p = new Parser(text);
        Object v = p.parseValue();
        p.skipWs();
        if (!p.atEnd()) throw new JsonException("trailing content at offset " + p.pos);
        return v;
    }

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) { this.s = s; }

        boolean atEnd() { return pos >= s.length(); }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        char peek() {
            if (atEnd()) throw new JsonException("unexpected end of input");
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
            if (!s.startsWith(w, pos)) throw new JsonException("invalid token at offset " + pos);
            pos += w.length();
        }

        Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') { pos++; return map; }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                expect(':');
                Object val = parseValue();
                map.put(key, val);
                skipWs();
                char c = peek();
                if (c == ',') { pos++; continue; }
                if (c == '}') { pos++; return map; }
                throw new JsonException("expected ',' or '}' at offset " + pos);
            }
        }

        List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWs();
            if (peek() == ']') { pos++; return list; }
            while (true) {
                list.add(parseValue());
                skipWs();
                char c = peek();
                if (c == ',') { pos++; continue; }
                if (c == ']') { pos++; return list; }
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
                    if (atEnd()) throw new JsonException("unterminated escape");
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

        Object parseNumber() {
            int start = pos;
            if (!atEnd() && s.charAt(pos) == '-') pos++;
            while (!atEnd()) {
                char c = s.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') pos++;
                else break;
            }
            if (start == pos) throw new JsonException("invalid value at offset " + pos);
            String token = s.substring(start, pos);
            try {
                return Double.valueOf(token);
            } catch (NumberFormatException ex) {
                throw new JsonException("invalid number '" + token + "'");
            }
        }
    }

    // ---------- serializing ----------

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, false, 0, -1);
        return sb.toString();
    }

    /** Deterministic serialization: object keys sorted, stable number format. */
    public static String canonical(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, true, 0, -1);
        return sb.toString();
    }

    public static String canonicalPretty(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, true, 0, 2);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object v, boolean sortKeys, int depth, int indent) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof String str) { writeString(sb, str); return; }
        if (v instanceof Boolean b) { sb.append(b); return; }
        if (v instanceof Number n) { sb.append(formatNumber(n.doubleValue())); return; }
        if (v instanceof Map<?, ?> map) {
            Map<String, Object> m = (Map<String, Object>) map;
            if (sortKeys) m = new TreeMap<>(m);
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) sb.append(',');
                newline(sb, depth + 1, indent);
                writeString(sb, e.getKey());
                sb.append(':');
                if (indent > 0) sb.append(' ');
                writeValue(sb, e.getValue(), sortKeys, depth + 1, indent);
                first = false;
            }
            if (!m.isEmpty()) newline(sb, depth, indent);
            sb.append('}');
            return;
        }
        if (v instanceof List<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object o : list) {
                if (!first) sb.append(',');
                newline(sb, depth + 1, indent);
                writeValue(sb, o, sortKeys, depth + 1, indent);
                first = false;
            }
            if (!list.isEmpty()) newline(sb, depth, indent);
            sb.append(']');
            return;
        }
        throw new JsonException("cannot serialize " + v.getClass());
    }

    private static void newline(StringBuilder sb, int depth, int indent) {
        if (indent <= 0) return;
        sb.append('\n');
        sb.append(" ".repeat(depth * indent));
    }

    static String formatNumber(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d))
            throw new JsonException("refusing to serialize non-finite number");
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ---------- typed accessors ----------

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object v, String what) {
        if (!(v instanceof Map)) throw new JsonException(what + " must be an object");
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object v, String what) {
        if (!(v instanceof List)) throw new JsonException(what + " must be an array");
        return (List<Object>) v;
    }

    public static String str(Object v, String what) {
        if (!(v instanceof String)) throw new JsonException(what + " must be a string");
        return (String) v;
    }

    public static double num(Object v, String what) {
        if (!(v instanceof Number)) throw new JsonException(what + " must be a number");
        return ((Number) v).doubleValue();
    }

    public static boolean bool(Object v, String what) {
        if (!(v instanceof Boolean)) throw new JsonException(what + " must be a boolean");
        return (Boolean) v;
    }
}
