package ctr;

import java.util.regex.Pattern;

public final class Validate {
    public static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}");

    private Validate() {
    }

    public static String id(String value, String label) {
        if (value == null || !ID.matcher(value).matches()) {
            throw new ApiException(400, label + " must use 1-64 letters, digits, '_', '.', ':' or '-'");
        }
        return value;
    }

    public static void finite(double value, String label) {
        if (!Double.isFinite(value)) {
            throw new ApiException(400, label + " must be finite");
        }
    }

    public static void nonNegative(double value, String label) {
        finite(value, label);
        if (value < 0) {
            throw new ApiException(400, label + " must be non-negative");
        }
    }
}
