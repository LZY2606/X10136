package ctstation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Minimal deterministic JSON parser / writer with zero dependencies.
 *
 * <p>Determinism notes used by the whole station:
 * <ul>
 *   <li>non-finite numbers (NaN / Infinity) are rejected by the parser and the
 *       writer,</li>
 *   <li>{@link #canonical(Object)} sorts object keys and is used for the job
 *       fingerprint and for portable exports,</li>
 *   <li>floating point output always goes through {@link #num(double)}, which
 *       normalises negative zero and uses {@link Double#toString(double)}
 *       (the canonical shortest round-trippable representation).</li>
 * </ul>
 */
public final class Json {

    private Json() {}

    // --------------------------------------------------------------- parsing

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.readValue();
        p.skipWs();
        if (p.pos < p.s.length()) {
            throw p.error("trailing characters");
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new AppException("bad-json", "expected a JSON object");
        }
        return (Map<String, Object>) v;
    }

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) { this.s = s; }

        AppException error(String msg) {
            return new AppException("bad-json", msg + " at position " + pos);
        }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object readValue() {
            skipWs();
            if (pos >= s.length()) throw error("unexpected end of input");
            char c = s.charAt(pos);
            switch (c) {
                case '{': return readObject();
                case '[': return readArray();
                case '"': return readString();
                case 't': return readLiteral("true", Boolean.TRUE);
                case 'f': return readLiteral("false", Boolean.FALSE);
                case 'n': return readLiteral("null", null);
                default: return readNumber();
            }
        }

        Object readLiteral(String lit, Object value) {
            if (!s.startsWith(lit, pos)) throw error("invalid literal");
            pos += lit.length();
            return value;
        }

        Map<String, Object> readObject() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++; // {
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '}') { pos++; return m; }
            while (true) {
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != '"') {
                    throw error("expected property name");
                }
                String key = readString();
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != ':') {
                    throw error("expected ':'");
                }
                pos++;
                Object value = readValue();
                m.put(key, value);
                skipWs();
                if (pos >= s.length()) throw error("unterminated object");
                char c = s.charAt(pos);
                if (c == ',') { pos++; continue; }
                if (c == '}') { pos++; return m; }
                throw error("expected ',' or '}'");
            }
        }

        List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ']') { pos++; return list; }
            while (true) {
                list.add(readValue());
                skipWs();
                if (pos >= s.length()) throw error("unterminated array");
                char c = s.charAt(pos);
                if (c == ',') { pos++; continue; }
                if (c == ']') { pos++; return list; }
                throw error("expected ',' or ']'");
            }
        }

        String readString() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= s.length()) throw error("bad escape");
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
                            if (pos + 4 > s.length()) throw error("bad unicode escape");
                            try {
                                sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            } catch (NumberFormatException nfe) {
                                throw error("bad unicode escape");
                            }
                            pos += 4;
                            break;
                        default: throw error("bad escape character");
                    }
                } else {
                    sb.append(c);
                }
            }
            throw error("unterminated string");
        }

        Object readNumber() {
            int start = pos;
            if (s.charAt(pos) == '-') pos++;
            boolean isFp = false;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    isFp = true;
                    pos++;
                } else {
                    break;
                }
            }
            String token = s.substring(start, pos);
            if (token.isEmpty() || "-".equals(token)) throw error("bad number");
            // Reject NaN / Infinity explicitly: they never match the grammar
            // above, this guard is defensive.
            if (token.contains("I") || token.contains("N")) throw error("bad number");
            try {
                if (isFp) {
                    double d = Double.parseDouble(token);
                    if (!Double.isFinite(d)) throw error("non-finite number");
                    return d;
                }
                return Long.parseLong(token);
            } catch (NumberFormatException nfe) {
                throw error("bad number '" + token + "'");
            }
        }
    }

    // ---------------------------------------------------------------- writing

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        writeInto(sb, v, false, 0);
        return sb.toString();
    }

    public static String pretty(Object v) {
        StringBuilder sb = new StringBuilder();
        writeInto(sb, v, true, 0);
        return sb.toString();
    }

    /** Canonical form: sorted object keys, no insignificant whitespace. */
    public static String canonical(Object v) {
        StringBuilder sb = new StringBuilder();
        writeCanonical(sb, v);
        return sb.toString();
    }

    public static String num(double d) {
        if (!Double.isFinite(d)) {
            throw new AppException("non-finite", "cannot serialise non-finite number");
        }
        if (d == 0.0d) return "0";
        double a = Math.abs(d);
        if (a >= 1e-3d && a < 1e15d && d == Math.rint(d)) {
            return Long.toString((long) d);
        }
        return Double.toString(d);
    }

    @SuppressWarnings("unchecked")
    private static void writeInto(StringBuilder sb, Object v, boolean pretty, int indent) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof Boolean || v instanceof Long) { sb.append(v.toString()); return; }
        if (v instanceof Integer) { sb.append(v.toString()); return; }
        if (v instanceof Number) { sb.append(num(((Number) v).doubleValue())); return; }
        if (v instanceof CharSequence) { writeString(sb, v.toString()); return; }
        if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                if (pretty) sb.append('\n').append(pad(indent + 1));
                writeString(sb, e.getKey());
                sb.append(pretty ? ": " : ":");
                writeInto(sb, e.getValue(), pretty, indent + 1);
            }
            if (pretty) sb.append('\n').append(pad(indent));
            sb.append('}');
            return;
        }
        if (v.getClass().isArray()) { v = asList(v); }
        if (v instanceof Iterable) {
            List<Object> list = new ArrayList<>();
            for (Object o : (Iterable<?>) v) list.add(o);
            if (list.isEmpty()) { sb.append("[]"); return; }
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                if (pretty) sb.append('\n').append(pad(indent + 1));
                writeInto(sb, list.get(i), pretty, indent + 1);
            }
            if (pretty) sb.append('\n').append(pad(indent));
            sb.append(']');
            return;
        }
        throw new AppException("bad-json", "cannot serialise " + v.getClass());
    }

    @SuppressWarnings("unchecked")
    private static void writeCanonical(StringBuilder sb, Object v) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof Boolean || v instanceof Long || v instanceof Integer) {
            sb.append(v.toString());
            return;
        }
        if (v instanceof Number) { sb.append(num(((Number) v).doubleValue())); return; }
        if (v instanceof CharSequence) { writeString(sb, v.toString()); return; }
        if (v instanceof Map) {
            TreeMap<String, Object> sorted = new TreeMap<>(String::compareTo);
            sorted.putAll((Map<String, Object>) v);
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, e.getKey());
                sb.append(':');
                writeCanonical(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (v.getClass().isArray()) { v = asList(v); }
        if (v instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object o : (Iterable<?>) v) {
                if (!first) sb.append(',');
                first = false;
                writeCanonical(sb, o);
            }
            sb.append(']');
            return;
        }
        throw new AppException("bad-json", "cannot canonicalise " + v.getClass());
    }

    private static String pad(int level) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < level; i++) sb.append("  ");
        return sb.toString();
    }

    static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }


    static List<Object> asList(Object array) {
        List<Object> list = new ArrayList<>();
        if (array instanceof Object[]) {
            Collections.addAll(list, (Object[]) array);
        } else if (array instanceof double[]) {
            for (double d : (double[]) array) list.add(d);
        } else if (array instanceof int[]) {
            for (int d : (int[]) array) list.add((long) d);
        } else if (array instanceof long[]) {
            for (long d : (long[]) array) list.add(d);
        } else if (array instanceof boolean[]) {
            for (boolean d : (boolean[]) array) list.add(d);
        } else {
            throw new AppException("bad-json", "unsupported array type");
        }
        return list;
    }

    // ------------------------------------------------------------- accessors

    public static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof String)) {
            throw new AppException("bad-request", "field '" + key + "' must be a string");
        }
        return (String) v;
    }

    public static String optStr(Map<String, Object> m, String key, String dflt) {
        Object v = m.get(key);
        return v == null ? dflt : v.toString();
    }

    public static double dbl(Object v, String what) {
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (!Double.isFinite(d)) {
                throw new AppException("non-finite", what + " must be finite");
            }
            return d;
        }
        throw new AppException("bad-request", what + " must be a number");
    }

    public static double dbl(Map<String, Object> m, String key) {
        if (!m.containsKey(key)) {
            throw new AppException("bad-request", "missing field '" + key + "'");
        }
        return dbl(m.get(key), "field '" + key + "'");
    }

    public static boolean optBool(Map<String, Object> m, String key, boolean dflt) {
        Object v = m.get(key);
        return v == null ? dflt : (v instanceof Boolean ? (Boolean) v : dflt);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Map) return (Map<String, Object>) v;
        throw new AppException("bad-request", "field '" + key + "' must be an object");
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof List) return (List<Object>) v;
        throw new AppException("bad-request", "field '" + key + "' must be an array");
    }

    public static LinkedHashMap<String, Object> object(Object... kv) {
        if ((kv.length & 1) != 0) throw new IllegalArgumentException("kv must be paired");
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
