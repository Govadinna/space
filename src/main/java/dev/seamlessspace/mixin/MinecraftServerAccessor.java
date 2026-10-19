package dev.seamlessspace.mixin;

import java.util.Map;
import java.util.concurrent.Executor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Access the same level collection and storage used by vanilla dimensions. */
@Mixin(MinecraftServer.class)
public interface MinecraftServerAccessor {
    @Accessor("levels")
    Map<ResourceKey<Level>, ServerLevel> seamlessspace$levels();

    @Accessor("storageSource")
    LevelStorageSource.LevelStorageAccess seamlessspace$storageSource();

    @Accessor("executor")
    Executor seamlessspace$executor();
}
