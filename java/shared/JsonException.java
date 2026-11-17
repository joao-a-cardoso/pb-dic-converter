package shared;

/**
 * The input is not valid JSON. Carries the position (0-based character index)
 * where the problem was found, which is also part of the message.
 */
public class JsonException extends IllegalArgumentException {

	private static final long serialVersionUID = 1L;

	private final int position;

	public JsonException(String message, int position) {
		super(message + " at " + position);
		this.position = position;
	}

	/** 0-based character index in the parsed text. */
	public int position() {
		return position;
	}
}
