package claudechat;

import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.permissions.PermissionSet;

/**
 * The permissions one question runs with, captured from the settings when it's asked. Its Claude Code flags, its
 * system prompt and every game command it runs follow this, so changing {@code /claude permissions} or
 * {@code /claude cheats} mid-question only affects the next one.
 * <p>
 * The tier decides <em>whether</em> a game command may change the world; cheats only decide the permission level it
 * runs at. Commands always run as the player who owns the world.
 *
 * @param cheats whether game commands run at command block level at least, even where the player can't cheat
 */
public record Policy(PermissionTier tier, boolean cheats) {
	public static Policy of(ClaudeConfig config) {
		return new Policy(config.permissions, config.cheats);
	}

	/** What run_command does with a command: one that only reads always runs, in every tier. */
	public PermissionTier.CommandRule commandRule(boolean readOnly) {
		return readOnly ? PermissionTier.CommandRule.RUN : tier.changes;
	}

	/**
	 * The permissions a command runs with, given the player's own. Cheats raise them to command block level
	 * (GAMEMASTERS); a player who already has more keeps it. Not {@code PermissionSet.union}: in 26.3 it keeps the
	 * lower of two levels.
	 */
	public PermissionSet permissions(PermissionSet own) {
		if (!cheats) {
			return own;
		}

		boolean enough = own instanceof LevelBasedPermissionSet level && level.level().isEqualOrHigherThan(PermissionLevel.GAMEMASTERS);
		return enough ? own : LevelBasedPermissionSet.GAMEMASTER;
	}
}
