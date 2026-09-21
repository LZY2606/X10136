package ctstation;

/** Structured application error surfaced as a JSON diagnostic. */
public class AppException extends RuntimeException {
    public final String code;

    public AppException(String code, String message) {
        super(message);
        this.code = code;
    }
}
