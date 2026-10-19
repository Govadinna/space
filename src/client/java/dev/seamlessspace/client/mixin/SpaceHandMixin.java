package dev.seamlessspace.client.mixin;

import dev.seamlessspace.client.SeamlessSpaceClient;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class SpaceHandMixin {
    @Inject(method = "render3dHud", at = @At("HEAD"), cancellable = false)
    private void seamlessspace$drawBeforeHand(CallbackInfo callback) {
        SeamlessSpaceClient.drawOrbitalScene();
    }
}
