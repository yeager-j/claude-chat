package claudechat;

import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * One {@code @claude} question, from the moment it's asked until it's answered, fails or is cancelled. It owns
 * everything that question may act with: the permissions captured when it was asked, its MCP token, its approval
 * requests and the claude process. {@link #cancel} takes all of them away at once: claude and every process it started
 * are killed, the token is revoked (interrupting tool calls in flight), and its approvals are denied. A cancelled
 * question's outcome is never delivered, so its answer and its session can't reach a later conversation.
 * <p>
 * {@link ClaudeCli} runs the question; this class only holds its state, and is safe to use from any thread.
 */
public final class ClaudeRun {
	/**
	 * What was asked, captured on the client thread.
	 *
	 * @param gameContext the game state for the system prompt ({@link GameContext#describe})
	 * @param namespaces  the mod ids of whatever the player is holding or looking at; a data-only mod among them, or one
	 *                    the question names, is decompiled first
	 * @param resume      the session to continue, or empty for a new conversation
	 */
	public record Request(String question, String gameContext, Set<String> namespaces, Optional<String> resume, Policy policy) {
	}

	/**
	 * How the question ended.
	 *
	 * @param text      the answer, or a message for the player when {@code failed}
	 * @param sessionId the conversation to resume for a follow-up; follow-ups continue it even after an error
	 */
	public record Outcome(String text, boolean failed, Optional<String> sessionId) {
		static Outcome failure(String message) {
			return new Outcome(message, true, Optional.empty());
		}
	}

	private final Request request;
	private final Approvals approvals;
	private final CompletableFuture<Outcome> outcome = new CompletableFuture<>();
	private final Object lock = new Object();
	private boolean cancelled;
	private ProcessTree process;
	private McpServer.Grant grant;
	private Thread thread;

	/** @param approvalStatus shows approval requests and answers in the question's status line; any thread */
	ClaudeRun(Request request, Consumer<Component> approvalStatus) {
		this.request = request;
		this.approvals = new Approvals(approvalStatus);
	}

	public Request request() {
		return request;
	}

	Approvals approvals() {
		return approvals;
	}

	/** Completes with the outcome; cancelled (and never completed otherwise) when the question is cancelled. */
	public CompletableFuture<Outcome> outcome() {
		return outcome;
	}

	public boolean isCancelled() {
		synchronized (lock) {
			return cancelled;
		}
	}

	/**
	 * Ends the question for good: kills claude and its descendants, revokes the MCP token, denies its approvals and
	 * stops its thread. Nothing it was doing is reported. Safe to call more than once, from any thread.
	 */
	public void cancel() {
		ProcessTree running;
		McpServer.Grant access;
		Thread worker;
		synchronized (lock) {
			if (cancelled) {
				return;
			}

			cancelled = true;
			running = process;
			access = grant;
			worker = thread;
		}

		outcome.cancel(false);
		approvals.close();
		if (access != null) {
			access.close();
		}
		if (running != null) {
			running.close();
		}
		if (worker != null) {
			worker.interrupt();
		}
	}

	/** Starts the question on a thread of its own, running {@code body}. */
	void start(Runnable body) {
		Thread worker = new Thread(body, "Claude Chat question");
		worker.setDaemon(true);
		synchronized (lock) {
			thread = worker;
		}

		worker.start();
	}

	/** Delivers the outcome, unless the question was cancelled. */
	void complete(Outcome result) {
		outcome.complete(result);
	}

	/**
	 * This question's MCP access, opened once; tool calls with its token get the question's policy and approvals.
	 *
	 * @throws CancellationException once the question is cancelled, so a cancel can't miss a token
	 */
	McpServer.Grant grant(McpServer mcp) {
		synchronized (lock) {
			throwIfCancelled();
			if (grant == null) {
				grant = mcp.grant(new Tool.Context(request.policy(), approvals));
			}

			return grant;
		}
	}

	/**
	 * Starts claude for this question, as the root of a {@link ProcessTree}: {@link #endProcess} or a cancel kills it
	 * and everything it started.
	 *
	 * @throws CancellationException once the question is cancelled, so a cancel can't miss a process
	 */
	Process startProcess(ProcessBuilder builder) throws IOException {
		synchronized (lock) {
			throwIfCancelled();
			process = ProcessTree.start(builder);
			return process.process();
		}
	}

	/**
	 * Kills the process from {@link #startProcess} and everything it started, however claude ended: a shell command or
	 * helper it left behind would otherwise keep the player's authority after the question.
	 */
	void endProcess() {
		ProcessTree running;
		synchronized (lock) {
			running = process;
		}
		if (running != null) {
			running.close();
		}
	}

	/** Throws if the question was cancelled; called where continuing would act for a question nobody waits for. */
	void throwIfCancelled() {
		synchronized (lock) {
			if (cancelled) {
				throw new CancellationException("The question was cancelled.");
			}
		}
	}
}
