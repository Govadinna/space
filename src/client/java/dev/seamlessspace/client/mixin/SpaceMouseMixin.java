package dev.seamlessspace.client.mixin;

import dev.seamlessspace.client.SeamlessSpaceClient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class SpaceMouseMixin {
    @ModifyVariable(method = "turn", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private double seamlessspace$zoomYaw(double value) { return scaled(value); }
    @ModifyVariable(method = "turn", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private double seamlessspace$zoomPitch(double value) { return scaled(value); }
    private double scaled(double value) {
        var player = Minecraft.getInstance().player;
        return player != null && (Object)this == player && player.isScoping()
                ? value * SeamlessSpaceClient.telescopeFovScale() : value;
    }
    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void seamlessspace$turn(double yaw, double pitch, CallbackInfo callback) {
        if (SeamlessSpaceClient.isActive() && (Object) this == Minecraft.getInstance().player) {
            SeamlessSpaceClient.turn(yaw, pitch);
            // Vanilla still rotates the actual creative player.
        }
    }
}
