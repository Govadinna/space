package dev.seamlessspace.client.mixin;

import dev.seamlessspace.client.SeamlessSpaceClient;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class SpaceTelescopeWheelMixin {
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void seamlessspace$zoom(long window, double x, double y, CallbackInfo ci) {
        if (SeamlessSpaceClient.scrollTelescope(y)) ci.cancel();
    }
}
