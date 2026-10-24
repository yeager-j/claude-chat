package claudechat;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/** Registries and data fixers for unit tests that read blocks; safe to call from every test class. */
final class GameBootstrap {
	private GameBootstrap() {
	}

	static synchronized void init() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}
}
