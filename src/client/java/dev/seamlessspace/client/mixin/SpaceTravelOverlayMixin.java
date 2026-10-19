package dev.seamlessspace.client.mixin;

import dev.seamlessspace.client.SeamlessSpaceClient;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class SpaceTravelOverlayMixin {
    @Inject(method = "render", at = @At("RETURN"))
    private void seamlessspace$holdOrbitUntilChunksArrive(CallbackInfo callback) {
        SeamlessSpaceClient.drawPendingTravel();
    }
}
