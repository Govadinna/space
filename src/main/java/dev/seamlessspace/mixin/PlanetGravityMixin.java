package dev.seamlessspace.mixin;
import dev.seamlessspace.world.*;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Multiplies native gravity for players, mobs and dropped items on both sides. */
@Mixin(Entity.class)
public abstract class PlanetGravityMixin {
    @Inject(method="getGravity",at=@At("RETURN"),cancellable=true)
    private void seamlessspace$gravity(CallbackInfoReturnable<Double> ci) {
        var entity=(Entity)(Object)this;
        var planet=PlanetWorlds.planetForDimension(entity.level().dimension());
        if(planet!=null)ci.setReturnValue(ci.getReturnValue()*PlanetPhysics.gravity(planet));
    }
}
