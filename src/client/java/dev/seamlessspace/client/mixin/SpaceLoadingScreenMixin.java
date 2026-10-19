package dev.seamlessspace.client.mixin;
import dev.seamlessspace.client.SeamlessSpaceClient;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Only our own prepared transfers omit the GUI; initial world loading is untouched. */
@Mixin(Gui.class)
public abstract class SpaceLoadingScreenMixin {
    @Inject(method="setScreen",at=@At("HEAD"),cancellable=true)
    private void seamlessspace$omitTravelScreen(Screen screen,CallbackInfo ci) {
        if(screen instanceof LevelLoadingScreen && SeamlessSpaceClient.isTravelPending()) {
            SeamlessSpaceClient.noteSuppressedLoadingScreen();ci.cancel();
        }
    }
}
