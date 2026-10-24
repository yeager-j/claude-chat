package claudechat;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;
import java.util.Set;

/** A plain-text snapshot of what the player is doing, so questions like "what is this?" just work. Call on the client thread. */
public final class GameContext {
	private static final Set<String> HIDDEN_MODS = Set.of("minecraft", "java", "fabricloader", "fabric-api", "mixinextras", ClaudeChatClient.MOD_ID);

	private GameContext() {
	}

	public static String describe(Minecraft mc) {
		StringBuilder sb = new StringBuilder();
		sb.append("Minecraft Java Edition ").append(SharedConstants.getCurrentVersion().name()).append(" with Fabric.\n");
		// Single-player or multiplayer is stated by ClaudeCli's prompt, from whether the world tools are offered.

		LocalPlayer player = mc.player;
		Level level = mc.level;
		if (player != null && level != null) {
			BlockPos pos = player.blockPosition();
			if (mc.gameMode != null) {
				sb.append("Game mode: ").append(mc.gameMode.getPlayerMode().getName()).append('\n');
			}
			sb.append("Dimension: ").append(level.dimension().identifier()).append('\n');
			sb.append("Biome: ").append(level.getBiome(pos).getRegisteredName()).append('\n');
			sb.append("Position: ").append(pos.getX()).append(' ').append(pos.getY()).append(' ').append(pos.getZ()).append('\n');
			sb.append("Health: ").append(Math.round(player.getHealth())).append('/').append(Math.round(player.getMaxHealth())).append('\n');
			sb.append("Weather: ").append(level.isRaining() ? "raining" : "clear").append('\n');

			ItemStack held = player.getMainHandItem();
			sb.append("Holding: ").append(held.isEmpty() ? "nothing"
				: held.getCount() + "x " + held.getHoverName().getString() + " (" + held.typeHolder().getRegisteredName() + ")").append('\n');

			if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
				var state = level.getBlockState(hit.getBlockPos());
				sb.append("Looking at block: ").append(state.getBlock().getName().getString())
					.append(" (").append(state.typeHolder().getRegisteredName()).append(")\n");
			}
		}

		List<String> mods = userMods().stream()
			.map(ModContainer::getMetadata)
			.map(GameContext::modLabel)
			.sorted(String.CASE_INSENSITIVE_ORDER)
			.toList();
		sb.append("Installed mods: ").append(mods.isEmpty() ? "none (vanilla)" : String.join(", ", mods)).append('\n');
		return sb.toString();
	}

	/** Mod namespaces of the held item and the block being looked at (not "minecraft"), e.g. "create". Client thread. */
	public static String modNamespaces(Minecraft mc) {
		Set<String> namespaces = new java.util.LinkedHashSet<>();
		if (mc.player != null) {
			mc.player.getMainHandItem().typeHolder().unwrapKey().ifPresent(key -> namespaces.add(key.identifier().getNamespace()));
		}
		if (mc.level != null && mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
			mc.level.getBlockState(hit.getBlockPos()).typeHolder().unwrapKey().ifPresent(key -> namespaces.add(key.identifier().getNamespace()));
		}
		namespaces.remove("minecraft");
		return String.join(" ", namespaces);
	}

	/** The player's own mods: top-level jars, without Minecraft, Java, Fabric itself or this mod. */
	public static List<ModContainer> userMods() {
		return FabricLoader.getInstance().getAllMods().stream()
			.filter(mod -> mod.getContainingMod().isEmpty())
			.filter(mod -> {
				String id = mod.getMetadata().getId();
				return !HIDDEN_MODS.contains(id) && !id.startsWith("fabric-");
			})
			.toList();
	}

	private static String modLabel(ModMetadata meta) {
		return meta.getName() + " " + meta.getVersion().getFriendlyString();
	}
}
