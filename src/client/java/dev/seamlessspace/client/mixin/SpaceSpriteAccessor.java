package dev.seamlessspace.client.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read resource-pack colors once per atlas reload for subpixel texture filtering. */
@Mixin(SpriteContents.class)
public interface SpaceSpriteAccessor {
    @Accessor("originalImage") NativeImage seamlessspace$image();
}
