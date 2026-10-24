package claudechat;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SmokeTest {
	@Test
	void minecraftClassesLoad() {
		assertEquals("stone", Identifier.parse("minecraft:stone").getPath());
	}
}
