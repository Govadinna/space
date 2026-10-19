package dev.seamlessspace.mixin;

import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A chunk's managed blocking loop can run a queued landing while vanilla is iterating worlds.
 * Keep each traversal stable when that landing adds its newly visited planet to the server.
 */
@Mixin(MinecraftServer.class)
abstract class MinecraftServerLevelsMixin {
    @Inject(method = "getAllLevels", at = @At("RETURN"), cancellable = true)
    private void seamlessspace$snapshotLevels(CallbackInfoReturnable<Iterable<ServerLevel>> callback) {
        callback.setReturnValue(List.copyOf(((MinecraftServerAccessor) this).seamlessspace$levels().values()));
    }

    // The login packet retains this set until the network thread encodes it. Do not send a
    // mutable keySet view which a concurrently arriving landing request could change.
    @Inject(method = "levelKeys", at = @At("RETURN"), cancellable = true)
    private void seamlessspace$snapshotLevelKeys(CallbackInfoReturnable<Set<ResourceKey<Level>>> callback) {
        callback.setReturnValue(Set.copyOf(((MinecraftServerAccessor) this).seamlessspace$levels().keySet()));
    }
}
