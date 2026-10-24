package claudechat;

import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A tool call's arguments, each parsed once by its {@link Param} before the tool runs. Arguments the tool doesn't
 * declare are ignored; a declared one that is missing when required, or of the wrong type, fails the whole call.
 */
public final class Arguments {
	private final Map<Param<?>, Object> values;

	private Arguments(Map<Param<?>, Object> values) {
		this.values = values;
	}

	/**
	 * Parses every one of {@code params} from {@code json}.
	 *
	 * @throws IllegalArgumentException naming the first argument that's missing or malformed
	 */
	public static Arguments parse(List<Param<?>> params, JsonObject json) {
		Map<Param<?>, Object> values = new HashMap<>();
		for (Param<?> param : params) {
			values.put(param, param.read(json));
		}

		return new Arguments(values);
	}

	/**
	 * The parsed value of {@code param}.
	 *
	 * @throws IllegalStateException if the tool didn't declare {@code param}, so its schema doesn't mention it
	 */
	@SuppressWarnings("unchecked")
	public <T> T get(Param<T> param) {
		if (!values.containsKey(param)) {
			throw new IllegalStateException(param.name() + " isn't one of this tool's declared arguments");
		}

		return (T) values.get(param);
	}
}
