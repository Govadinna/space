package dev.seamlessspace.client.mixin;

import dev.seamlessspace.client.SeamlessSpaceClient;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Apply extended telescope optics to vanilla terrain and the orbital renderer alike. */
@Mixin(Camera.class)
public abstract class SpaceZoomMixin {
    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
    private void seamlessspace$telescope(float partialTick, CallbackInfoReturnable<Float> ci) {
        var player = Minecraft.getInstance().player;
        if (player != null && player.isScoping())
            ci.setReturnValue((float)(ci.getReturnValue() * SeamlessSpaceClient.telescopeFovScale()));
    }
}
