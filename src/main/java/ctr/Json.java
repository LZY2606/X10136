package ctr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Json {
    private Json() {
    }

    public static Object parse(String text) {
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.end()) {
            throw parser.error("Trailing content");
        }
        return value;
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    public static String pretty(Object value) {
        StringBuilder out = new StringBuilder();
        writePretty(value, out, 0);
        out.append('\n');
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value, String description) {
        if (!(value instanceof Map)) {
            throw new ApiException(400, description + " must be a JSON object");
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> array(Object value, String description) {
        if (!(value instanceof List)) {
            throw new ApiException(400, description + " must be a JSON array");
        }
        return (List<Object>) value;
    }

    public static String string(Map<String, Object> map, String key) {
        Object value = required(map, key);
        if (!(value instanceof String)) {
            throw new ApiException(400, key + " must be a string");
        }
        return (String) value;
    }

    public static String optionalString(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof String)) {
            throw new ApiException(400, key + " must be a string");
        }
        return (String) value;
    }

    public static boolean optionalBoolean(Map<String, Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Boolean)) {
            throw new ApiException(400, key + " must be a boolean");
        }
        return (Boolean) value;
    }

    public static double number(Object value, String description) {
        if (!(value instanceof Number)) {
            throw new ApiException(400, description + " must be a number");
        }
        double result = ((Number) value).doubleValue();
        if (!Double.isFinite(result)) {
            throw new ApiException(400, description + " must be finite");
        }
        return result;
    }

    public static double requiredNumber(Map<String, Object> map, String key) {
        return number(required(map, key), key);
    }

    public static Double optionalNumber(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        return number(value, key);
    }

    public static Object required(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new ApiException(400, "Missing required field: " + key);
        }
        return value;
    }

    public static Map<String, Object> objectField(Map<String, Object> map, String key) {
        return object(required(map, key), key);
    }

    public static List<Object> arrayField(Map<String, Object> map, String key) {
        return array(required(map, key), key);
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            writeString((String) value, out);
        } else if (value instanceof Boolean) {
            out.append(value.toString());
        } else if (value instanceof Integer || value instanceof Long) {
            out.append(value.toString());
        } else if (value instanceof Number) {
            out.append(numberString(((Number) value).doubleValue()));
        } else if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeString(String.valueOf(entry.getKey()), out);
                out.append(':');
                write(entry.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof Iterable) {
            out.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                write(item, out);
            }
            out.append(']');
        } else {
            writeString(value.toString(), out);
        }
    }

    private static void writePretty(Object value, StringBuilder out, int indent) {
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            if (map.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append("{\n");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.append(",\n");
                }
                first = false;
                indent(out, indent + 1);
                writeString(String.valueOf(entry.getKey()), out);
                out.append(": ");
                writePretty(entry.getValue(), out, indent + 1);
            }
            out.append('\n');
            indent(out, indent);
            out.append('}');
        } else if (value instanceof Iterable) {
            List<Object> list = new ArrayList<>();
            for (Object item : (Iterable<?>) value) { list.add(item); }
            if (list.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append("[\n");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(",\n");
                }
                indent(out, indent + 1);
                writePretty(list.get(i), out, indent + 1);
            }
            out.append('\n');
            indent(out, indent);
            out.append(']');
        } else {
            write(value, out);
        }
    }

    private static void indent(StringBuilder out, int count) {
        for (int i = 0; i < count; i++) {
            out.append("  ");
        }
    }

    private static void writeString(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }

    static String numberString(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("JSON cannot serialize non-finite number");
        }
        if (value == 0.0) {
            return "0";
        }
        if (Math.rint(value) == value && Math.abs(value) < 1e16) {
            return Long.toString((long) value);
        }
        String compact = Double.toString(value);
        return compact;
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        boolean end() {
            return pos >= text.length();
        }

        Object readValue() {
            skipWhitespace();
            if (end()) {
                throw error("Unexpected end of JSON");
            }
            char c = text.charAt(pos);
            if (c == '{') {
                return readObject();
            }
            if (c == '[') {
                return readArray();
            }
            if (c == '"') {
                return readString();
            }
            if (c == 't' || c == 'f') {
                return readBoolean();
            }
            if (c == 'n') {
                return readNull();
            }
            return readNumber();
        }

        Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            expect('{');
            skipWhitespace();
            if (consume('}')) {
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                Object value = readValue();
                map.put(key, value);
                skipWhitespace();
                if (consume('}')) {
                    return map;
                }
                expect(',');
            }
        }

        List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            expect('[');
            skipWhitespace();
            if (consume(']')) {
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWhitespace();
                if (consume(']')) {
                    return list;
                }
                expect(',');
            }
        }

        String readString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                if (end()) {
                    throw error("Unterminated string");
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    if (end()) {
                        throw error("Unterminated escape");
                    }
                    char escape = text.charAt(pos++);
                    switch (escape) {
                        case '"':
                            out.append('"');
                            break;
                        case '\\':
                            out.append('\\');
                            break;
                        case '/':
                            out.append('/');
                            break;
                        case 'b':
                            out.append('\b');
                            break;
                        case 'f':
                            out.append('\f');
                            break;
                        case 'n':
                            out.append('\n');
                            break;
                        case 'r':
                            out.append('\r');
                            break;
                        case 't':
                            out.append('\t');
                            break;
                        case 'u':
                            out.append(readUnicode());
                            break;
                        default:
                            throw error("Invalid escape");
                    }
                } else if (c < 0x20) {
                    throw error("Unescaped control character");
                } else {
                    out.append(c);
                }
            }
        }

        char readUnicode() {
            if (pos + 4 > text.length()) {
                throw error("Invalid unicode escape");
            }
            String hex = text.substring(pos, pos + 4);
            pos += 4;
            try {
                return (char) Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                throw error("Invalid unicode escape");
            }
        }

        Boolean readBoolean() {
            if (text.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw error("Invalid boolean");
        }

        Object readNull() {
            if (text.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw error("Invalid null");
        }

        Number readNumber() {
            int start = pos;
            if (peek('-') || peek('+')) {
                pos++;
            }
            readDigits();
            if (consume('.')) {
                readDigits();
            }
            if (peek('e') || peek('E')) {
                pos++;
                if (peek('-') || peek('+')) {
                    pos++;
                }
                readDigits();
            }
            String token = text.substring(start, pos);
            if (token.isEmpty() || token.equals("-") || token.equals("+")) {
                throw error("Invalid number");
            }
            double value;
            try {
                value = Double.parseDouble(token);
            } catch (NumberFormatException e) {
                throw error("Invalid number");
            }
            if (!Double.isFinite(value)) {
                throw error("Non-finite numbers are not valid JSON");
            }
            return value;
        }

        private void readDigits() {
            int start = pos;
            while (!end() && Character.isDigit(text.charAt(pos))) {
                pos++;
            }
            if (start == pos) {
                throw error("Expected digits");
            }
        }

        void skipWhitespace() {
            while (!end() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        boolean consume(char c) {
            if (!end() && text.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        void expect(char c) {
            if (end() || text.charAt(pos) != c) {
                throw error("Expected '" + c + "'");
            }
            pos++;
        }

        boolean peek(char c) {
            return !end() && text.charAt(pos) == c;
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException("Invalid JSON at position " + pos + ": " + message);
        }
    }
}
