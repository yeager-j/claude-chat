package claudechat.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The {@code CLAUDECHAT_*} environment, parsed once against the {@link Step} declarations. Parsing fails, naming the
 * known variables, on an unknown name, a switch that isn't {@code 1}, a schematic folder that doesn't exist, or a
 * question without its step's switch: a typo must never silently skip a step, and a question must never change the
 * world and then ask nothing.
 *
 * @param steps     the steps to run, always including those without a switch
 * @param questions real questions, by step
 * @param live      whether CLAUDECHAT_LIVE_TEST asks the real Claude
 */
record TestOptions(Set<Step> steps, Map<Step, String> questions, Optional<Path> schematicFolder, boolean live) {
	private static final String PREFIX = "CLAUDECHAT_";
	/** How long the fake's questions may run: the hang scenario waits this long. */
	private static final int FAKE_TIMEOUT_SECONDS = 20;
	/** How long a real question may run, as the mod's own default. */
	private static final int REAL_TIMEOUT_SECONDS = 300;

	static TestOptions parse(Map<String, String> environment) {
		Set<String> known = knownNames();
		List<String> unknown = environment.keySet().stream()
			.filter(name -> name.startsWith(PREFIX) && !known.contains(name)).sorted().toList();
		if (!unknown.isEmpty()) {
			throw new IllegalArgumentException("Unknown " + String.join(", ", unknown) + ". Known: " + String.join(", ", known));
		}

		Set<Step> steps = EnumSet.noneOf(Step.class);
		Map<Step, String> questions = new EnumMap<>(Step.class);
		for (Step step : Step.values()) {
			boolean on = step.switchName == null || switchedOn(step.switchName, environment.get(step.switchName));
			if (on) {
				steps.add(step);
			}

			Optional<String> question = step.questionName().map(environment::get).filter(text -> !text.isBlank());
			if (question.isPresent() && !on) {
				throw new IllegalArgumentException(step.questionName + " needs " + step.switchName + ": a question without its"
					+ " step would ask about a world the step never set up.");
			}

			question.ifPresent(text -> questions.put(step, text));
		}

		Optional<Path> schematics = Optional.ofNullable(environment.get(Step.Names.SCHEMATIC)).filter(text -> !text.isBlank()).map(Path::of);
		if (schematics.isPresent() && !Files.isDirectory(schematics.get())) {
			throw new IllegalArgumentException(Step.Names.SCHEMATIC + " must be a folder of schematics: " + schematics.get());
		}

		return new TestOptions(Set.copyOf(steps), Map.copyOf(questions), schematics, steps.contains(Step.LIVE));
	}

	/** Every variable some step declares. */
	static Set<String> knownNames() {
		Set<String> names = new TreeSet<>();
		for (Step step : Step.values()) {
			Stream.concat(Stream.of(step.switchName, step.questionName), step.alsoReads.stream())
				.filter(name -> name != null).forEach(names::add);
		}

		return names;
	}

	/** Set and non-blank turns a switch on: 1 for most, a folder for the schematic step. 0 or blank is off. */
	private static boolean switchedOn(String name, String value) {
		if (value == null || value.isBlank() || value.equals("0")) {
			return false;
		}

		if (!name.equals(Step.Names.SCHEMATIC) && !value.equals("1")) {
			throw new IllegalArgumentException(name + " must be 1 or 0, not \"" + value + "\".");
		}

		return true;
	}

	/** Whether any question goes to the real Claude, which needs the real timeout. */
	boolean asksRealClaude() {
		return live || !questions.isEmpty();
	}

	/**
	 * The question timeout this run's config file sets: long enough for real questions when there are any, otherwise
	 * short, since the hang scenario waits it out.
	 */
	int questionTimeoutSeconds() {
		return asksRealClaude() ? REAL_TIMEOUT_SECONDS : FAKE_TIMEOUT_SECONDS;
	}

	Optional<String> question(Step step) {
		return Optional.ofNullable(questions.get(step));
	}

	/** For the log: the steps and questions this run has. */
	String describe() {
		List<String> parts = new ArrayList<>();
		steps.stream().sorted().forEach(step -> parts.add(step.name() + (questions.containsKey(step) ? " (with a real question)" : "")));
		return String.join(", ", parts);
	}
}
