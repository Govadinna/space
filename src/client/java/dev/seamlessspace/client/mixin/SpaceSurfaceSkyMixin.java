package dev.seamlessspace.client.mixin;

import dev.seamlessspace.client.SeamlessSpaceClient;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draw after vanilla sky, before the terrain render pass is opened. */
@Mixin(LevelRenderer.class)
public abstract class SpaceSurfaceSkyMixin {
    @Inject(method = "lambda$addMainPass$0", at = @At("HEAD"))
    private void seamlessspace$drawPlanetSky(CallbackInfo callback) {
        SeamlessSpaceClient.drawSurfaceSky();
    }
}
