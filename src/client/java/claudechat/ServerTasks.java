package claudechat;

import net.minecraft.server.MinecraftServer;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * The integrated server's thread rules for the world tools, checked rather than written in comments: game state is
 * read on the server thread, and slow work (region files, searches) runs on the tool's own thread, handing the server
 * short tasks.
 */
public final class ServerTasks {
	private ServerTasks() {
	}

	public static void requireServerThread(MinecraftServer server) {
		if (!server.isSameThread()) {
			throw new IllegalStateException("Must run on the server thread, not " + Thread.currentThread().getName());
		}
	}

	/** Blocking on the server from its own thread would deadlock, and slow work there freezes the game. */
	public static void requireOffServerThread(MinecraftServer server) {
		if (server.isSameThread()) {
			throw new IllegalStateException("Must not run on the server thread");
		}
	}

	/**
	 * Runs {@code task} on the server thread and waits for it. If it doesn't finish in time, it's cancelled (it won't
	 * start if it hasn't yet) and the error names {@code what}. The task's own exception is rethrown as it is.
	 */
	public static <T> T call(MinecraftServer server, Supplier<T> task, Duration timeout, String what) throws Exception {
		requireOffServerThread(server);
		CompletableFuture<T> future = server.submit(task);
		try {
			return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			future.cancel(false);
			throw new IllegalStateException(what + " didn't finish within " + timeout.toSeconds() + " s; the game may be busy.");
		} catch (InterruptedException e) {
			future.cancel(false);
			throw e;
		} catch (ExecutionException e) {
			if (e.getCause() instanceof Exception cause) {
				throw cause;
			}
			throw e;
		}
	}
}
