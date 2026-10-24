package claudechat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PackOrderTest {
	/** Pack ids as the server lists them, and what the knowledge base has for each. */
	private static final Map<String, String> FOLDERS = Map.of(
		"vanilla", "minecraft",
		"create", "create",
		"create_library", "create",
		"sodium", "sodium",
		"file/Better Dungeons.zip", "pack: better dungeons");

	@Test
	void highestPriorityFirstLeavingOutUnknownPacks() {
		List<String> selected = List.of("vanilla", "fabric", "create_library", "sodium", "create", "minecart_improvements",
			"file/Better Dungeons.zip");

		List<String> order = KnowledgeBase.inPackOrder(selected, id -> Optional.ofNullable(FOLDERS.get(id)));

		assertEquals(List.of("pack: better dungeons", "create", "sodium", "minecraft"), order);
	}

	@Test
	void aDisabledPackIsLeftOut() {
		List<String> order = KnowledgeBase.inPackOrder(List.of("vanilla", "create"), id -> Optional.ofNullable(FOLDERS.get(id)));

		assertEquals(List.of("create", "minecraft"), order);
	}
}
