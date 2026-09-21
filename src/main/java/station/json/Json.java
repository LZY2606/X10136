package station.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 极简 JSON 解析/序列化。解析时接受 NaN / Infinity / -Infinity 字面量，
 * 以便上层校验能给出“非有限数字”诊断而不是解析崩溃。
 * 序列化是确定性的：相同数据在任何 JVM 上产生相同字节。
 */
public final class Json {

    private Json() {}

    // ---------- 解析 ----------

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (!p.atEnd()) throw new JsonException("第 " + p.pos + " 个字符后存在多余内容");
        return v;
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
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        Object value() {
            skipWs();
            if (atEnd()) throw new JsonException("意外的输入结束");
            char c = s.charAt(pos);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                case 'N': expect("NaN"); return Double.NaN;
                case 'I': expect("Infinity"); return Double.POSITIVE_INFINITY;
                case '-':
                    if (s.startsWith("-Infinity", pos)) { pos += 9; return Double.NEGATIVE_INFINITY; }
                    return number();
                default: return number();
            }
        }

        void expect(String lit) {
            if (!s.startsWith(lit, pos)) throw new JsonException("第 " + pos + " 个字符处期望 " + lit);
            pos += lit.length();
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++; // {
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '}') { pos++; return m; }
            while (true) {
                skipWs();
                String key = string();
                skipWs();
                if (atEnd() || s.charAt(pos) != ':') throw new JsonException("第 " + pos + " 个字符处期望 ':'");
                pos++;
                m.put(key, value());
                skipWs();
                if (atEnd()) throw new JsonException("对象未闭合");
                char c = s.charAt(pos);
                if (c == ',') { pos++; continue; }
                if (c == '}') { pos++; return m; }
                throw new JsonException("第 " + pos + " 个字符处期望 ',' 或 '}'");
            }
        }

        List<Object> array() {
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ']') { pos++; return list; }
            while (true) {
                list.add(value());
                skipWs();
                if (atEnd()) throw new JsonException("数组未闭合");
                char c = s.charAt(pos);
                if (c == ',') { pos++; continue; }
                if (c == ']') { pos++; return list; }
                throw new JsonException("第 " + pos + " 个字符处期望 ',' 或 ']'");
            }
        }

        String string() {
            if (atEnd() || s.charAt(pos) != '"') throw new JsonException("第 " + pos + " 个字符处期望字符串");
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) throw new JsonException("字符串未闭合");
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (atEnd()) throw new JsonException("转义序列不完整");
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
                            if (pos + 4 > s.length()) throw new JsonException("unicode 转义不完整");
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default: throw new JsonException("非法转义 \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Object number() {
            int start = pos;
            if (pos < s.length() && s.charAt(pos) == '-') pos++;
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            boolean isDouble = false;
            if (pos < s.length() && s.charAt(pos) == '.') {
                isDouble = true;
                pos++;
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                isDouble = true;
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            }
            if (start == pos) throw new JsonException("第 " + pos + " 个字符处期望数值");
            String text = s.substring(start, pos);
            try {
                if (!isDouble) return Long.parseLong(text);
                return Double.parseDouble(text);
            } catch (NumberFormatException e) {
                throw new JsonException("非法数值: " + text);
            }
        }
    }

    // ---------- 序列化 ----------

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, false);
        return sb.toString();
    }

    /** 键按字典序排序的规范形式，用于指纹与导出。 */
    public static String writeCanonical(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, true);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object v, boolean canonical) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            writeString(sb, (String) v);
        } else if (v instanceof Boolean) {
            sb.append(v.toString());
        } else if (v instanceof Long || v instanceof Integer) {
            sb.append(v.toString());
        } else if (v instanceof Double || v instanceof Float) {
            sb.append(formatNumber(((Number) v).doubleValue()));
        } else if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            if (canonical) m = new TreeMap<>(m);
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, e.getKey());
                sb.append(':');
                writeValue(sb, e.getValue(), canonical);
            }
            sb.append('}');
        } else if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<Object>) v) {
                if (!first) sb.append(',');
                first = false;
                writeValue(sb, o, canonical);
            }
            sb.append(']');
        } else {
            throw new JsonException("无法序列化类型: " + v.getClass());
        }
    }

    /** 确定性数值格式：整数打整数，其余走 BigDecimal 去尾零。 */
    public static String formatNumber(double d) {
        if (Double.isNaN(d)) return "NaN";
        if (d == Double.POSITIVE_INFINITY) return "Infinity";
        if (d == Double.NEGATIVE_INFINITY) return "-Infinity";
        if (d == Math.rint(d) && Math.abs(d) < 9.0e15) {
            return Long.toString((long) d);
        }
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    // ---------- 取值辅助 ----------

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object v, String what) {
        if (!(v instanceof Map)) throw new JsonException(what + " 必须是对象");
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object v, String what) {
        if (!(v instanceof List)) throw new JsonException(what + " 必须是数组");
        return (List<Object>) v;
    }

    public static String asString(Object v, String what) {
        if (!(v instanceof String)) throw new JsonException(what + " 必须是字符串");
        return (String) v;
    }

    public static double asDouble(Object v, String what) {
        if (v instanceof Double) return (Double) v;
        if (v instanceof Long) return ((Long) v).doubleValue();
        if (v instanceof Integer) return ((Integer) v).doubleValue();
        throw new JsonException(what + " 必须是数值");
    }

    public static boolean asBoolean(Object v, String what) {
        if (!(v instanceof Boolean)) throw new JsonException(what + " 必须是布尔值");
        return (Boolean) v;
    }
}
