package claudechat.mixin;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** A template piece's full template id. Subclasses override it to add their folder ("ship" → "end_city/ship"). */
@Mixin(TemplateStructurePiece.class)
public interface TemplateStructurePieceAccessor {
	@Invoker("makeTemplateLocation")
	Identifier claudechat$templateLocation();
}
