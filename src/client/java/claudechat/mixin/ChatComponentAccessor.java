package claudechat.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/** Vanilla can only delete signed player messages (leaving a marker), so reach in to replace our own status line. */
@Mixin(ChatComponent.class)
public interface ChatComponentAccessor {
	/** Newest first. */
	@Accessor("allMessages")
	List<GuiMessage> claudechat$allMessages();

	/** Re-lays out the visible lines after allMessages changes. */
	@Invoker("refreshTrimmedMessages")
	void claudechat$refreshTrimmedMessages();
}
