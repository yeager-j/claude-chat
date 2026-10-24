package claudechat;

import java.util.Optional;

/**
 * The questions asked in one world or server: the Claude Code session follow-ups resume, and the question running
 * right now. Leaving the world or {@code /claude reset} ends it: the running question is cancelled, and nothing from it
 * (its answer, its session) reaches the next conversation. Client thread only, apart from {@link #busy}.
 */
final class Conversation {
	private Optional<String> session = Optional.empty();
	private volatile ClaudeRun running;
	private boolean ended;

	/** Is a question being answered? A new one has to wait. */
	boolean busy() {
		return running != null;
	}

	/** The session a new question continues; empty for the first one. */
	Optional<String> session() {
		return session;
	}

	/** {@code run} answers this conversation's next question. */
	void started(ClaudeRun run) {
		if (ended) {
			run.cancel();
			return;
		}

		running = run;
	}

	/**
	 * {@code run} finished: follow-ups continue its session.
	 *
	 * @return false if the run no longer belongs to this conversation, so its outcome must not be shown
	 */
	boolean finished(ClaudeRun run, Optional<String> runSession) {
		if (ended || run != running) {
			return false;
		}

		running = null;
		runSession.ifPresent(id -> session = Optional.of(id));
		return true;
	}

	/** Cancels the running question. Nothing of this conversation is used again. */
	void end() {
		ended = true;
		ClaudeRun run = running;
		running = null;
		if (run != null) {
			run.cancel();
		}
	}
}
