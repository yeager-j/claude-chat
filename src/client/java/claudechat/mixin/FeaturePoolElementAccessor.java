package claudechat.mixin;

import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.pools.FeaturePoolElement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The placed feature a jigsaw piece places (village hay piles, lamps), which has no public getter. */
@Mixin(FeaturePoolElement.class)
public interface FeaturePoolElementAccessor {
	@Accessor("feature")
	Holder<PlacedFeature> claudechat$feature();
}
