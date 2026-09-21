package crstation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Minimal self-contained JSON parser / writer.
 * Values use Map<String,Object>, List<Object>, String, Double, Boolean, null.
 * Serialization is deterministic (keys sorted for the canonical form, fixed
 * number formatting), which makes job fingerprints portable across stores.
 */
public final class Json {

    private Json() {
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.readValue();
        p.skipWs();
        if (!p.eof()) {
            throw p.error("trailing characters");
        }
        return v;
    }

    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("expected a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) v;
        return m;
    }

    private static final class Parser {
        final String s;
        int i;

        Parser(String s) {
            this.s = s;
        }

        boolean eof() {
            return i >= s.length();
        }

        IllegalArgumentException error(String msg) {
            int line = 1, col = 1;
            for (int k = 0; k < i && k < s.length(); k++) {
                if (s.charAt(k) == '\n') {
                    line++;
                    col = 1;
                } else {
                    col++;
                }
            }
            return new IllegalArgumentException("JSON error at line " + line + " col " + col + ": " + msg);
        }

        void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    i++;
                } else {
                    break;
                }
            }
        }

        char peek() {
            if (eof()) {
                throw error("unexpected end of input");
            }
            return s.charAt(i);
        }

        Object readValue() {
            skipWs();
            char c = peek();
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't', 'f' -> readBool();
                case 'n' -> readNull();
                default -> readNumber();
            };
        }

        Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> m = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') {
                i++;
                return m;
            }
            while (true) {
                skipWs();
                String key = readString();
                skipWs();
                expect(':');
                Object val = readValue();
                m.put(key, val);
                skipWs();
                char c = next();
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    throw error("expected ',' or '}'");
                }
            }
        }

        List<Object> readArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWs();
            if (peek() == ']') {
                i++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWs();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw error("expected ',' or ']'");
                }
            }
        }

        String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) {
                    throw error("unterminated string");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (i + 4 > s.length()) {
                                throw error("bad unicode escape");
                            }
                            int code = Integer.parseInt(s.substring(i, i + 4), 16);
                            sb.append((char) code);
                            i += 4;
                        }
                        default -> throw error("bad escape");
                    }
                } else if (c < 0x20) {
                    throw error("unescaped control character");
                } else {
                    sb.append(c);
                }
            }
        }

        Object readNumber() {
            int start = i;
            if (peek() == '-') {
                i++;
            }
            while (!eof()) {
                char c = s.charAt(i);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    i++;
                } else {
                    break;
                }
            }
            String tok = s.substring(start, i);
            if (tok.isEmpty() || tok.equals("-")) {
                throw error("invalid number");
            }
            try {
                return Double.parseDouble(tok);
            } catch (NumberFormatException ex) {
                throw error("invalid number '" + tok + "'");
            }
        }

        Boolean readBool() {
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            throw error("invalid literal");
        }

        Object readNull() {
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            throw error("invalid literal");
        }

        char next() {
            if (eof()) {
                throw error("unexpected end of input");
            }
            return s.charAt(i++);
        }

        void expect(char c) {
            if (eof() || s.charAt(i) != c) {
                throw error("expected '" + c + "'");
            }
            i++;
        }
    }

    // ------------------------------------------------------------------
    // Typed accessors
    // ------------------------------------------------------------------

    public static Map<String, Object> obj(Object v) {
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("expected object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) v;
        return m;
    }

    public static List<Object> arr(Object v) {
        if (!(v instanceof List)) {
            throw new IllegalArgumentException("expected array");
        }
        @SuppressWarnings("unchecked")
        List<Object> l = (List<Object>) v;
        return l;
    }

    public static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof String)) {
            throw new IllegalArgumentException("field '" + key + "' must be a string");
        }
        return (String) v;
    }

    public static String optStr(Map<String, Object> m, String key, String dflt) {
        Object v = m.get(key);
        return v == null ? dflt : v.toString();
    }

    public static double num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof Number)) {
            throw new IllegalArgumentException("field '" + key + "' must be a number");
        }
        return ((Number) v).doubleValue();
    }

    public static double optNum(Map<String, Object> m, String key, double dflt) {
        Object v = m.get(key);
        return v instanceof Number ? ((Number) v).doubleValue() : dflt;
    }

    public static boolean optBool(Map<String, Object> m, String key, boolean dflt) {
        Object v = m.get(key);
        return v instanceof Boolean ? (Boolean) v : dflt;
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /** Human readable output, key order preserved. */
    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        writeInto(sb, v, false);
        return sb.toString();
    }

    public static String writePretty(Object v) {
        StringBuilder sb = new StringBuilder();
        writeInto(sb, v, true);
        return sb.toString();
    }

    /** Deterministic canonical form: object keys sorted, no whitespace. */
    public static String canonical(Object v) {
        StringBuilder sb = new StringBuilder();
        writeCanonical(sb, normalize(v));
        return sb.toString();
    }

    private static Object normalize(Object v) {
        if (v instanceof Map<?, ?> mm) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : mm.entrySet()) {
                sorted.put(String.valueOf(e.getKey()), normalize(e.getValue()));
            }
            return sorted;
        }
        if (v instanceof List<?> ll) {
            List<Object> out = new ArrayList<>(ll.size());
            for (Object o : ll) {
                out.add(normalize(o));
            }
            return out;
        }
        if (v instanceof Double d && d == 0.0) {
            // collapse -0.0 so fingerprints are stable
            return Double.valueOf(0.0);
        }
        return v;
    }

    private static void writeCanonical(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeCanonical(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof List<?> l) {
            sb.append('[');
            for (int k = 0; k < l.size(); k++) {
                if (k > 0) {
                    sb.append(',');
                }
                writeCanonical(sb, l.get(k));
            }
            sb.append(']');
        } else if (v instanceof Boolean b) {
            sb.append(b.booleanValue());
        } else if (v instanceof Number n) {
            sb.append(formatNumber(n.doubleValue()));
        } else {
            writeString(sb, v.toString());
        }
    }

    private static void writeInto(StringBuilder sb, Object v, boolean indent) {
        writeInto(sb, v, indent, 0);
    }

    private static void writeInto(StringBuilder sb, Object v, boolean indent, int depth) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                if (indent) {
                    sb.append('\n').append("  ".repeat(depth + 1));
                }
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                if (indent) {
                    sb.append(' ');
                }
                writeInto(sb, e.getValue(), indent, depth + 1);
            }
            if (indent) {
                sb.append('\n').append("  ".repeat(depth));
            }
            sb.append('}');
        } else if (v instanceof List<?> l) {
            if (l.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append('[');
            for (int k = 0; k < l.size(); k++) {
                if (k > 0) {
                    sb.append(',');
                }
                if (indent) {
                    sb.append('\n').append("  ".repeat(depth + 1));
                }
                writeInto(sb, l.get(k), indent, depth + 1);
            }
            if (indent) {
                sb.append('\n').append("  ".repeat(depth));
            }
            sb.append(']');
        } else if (v instanceof Boolean b) {
            sb.append(b.booleanValue());
        } else if (v instanceof Number n) {
            sb.append(formatNumber(n.doubleValue()));
        } else {
            writeString(sb, v.toString());
        }
    }

    static String formatNumber(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            // JSON has no representation; canonical paths reject such data
            // earlier, but never emit invalid JSON.
            return d > 0 ? "1e999" : "-1e999";
        }
        if (d == 0.0) {
            return "0";
        }
        double a = Math.abs(d);
        if (d == Math.rint(d) && a < 1e18) {
            return Long.toString((long) d);
        }
        return Double.toString(d);
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
