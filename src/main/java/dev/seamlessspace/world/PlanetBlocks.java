package dev.seamlessspace.world;
import java.util.*;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.*;
import net.minecraft.resources.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockBehaviour;
/** Planet-only variants leave vanilla Overworld models intact. */
public final class PlanetBlocks {
    private static final Map<PlanetMaterial,Block> BLOCKS=new EnumMap<>(PlanetMaterial.class);
    private static final Map<Block,PlanetMaterial> MATERIALS=new IdentityHashMap<>();
    private PlanetBlocks() { }
    public static void initialize() {
        for(var material:PlanetMaterial.values()) {
            if(material==PlanetMaterial.COLOR || material==PlanetMaterial.WATER || material==PlanetMaterial.LAVA || material.ordinal()>=19 && material.ordinal()<=25)continue;
            var id=Identifier.fromNamespaceAndPath("seamlessspace",material==PlanetMaterial.SUN_PLASMA?"solar_plasma":"regolith_"+material.name().toLowerCase(Locale.ROOT));
            var key=ResourceKey.create(Registries.BLOCK,id);
            var properties=BlockBehaviour.Properties.of().strength(1.5f).mapColor(PlanetChunkGenerator.materialState(material).getMapColor(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,net.minecraft.core.BlockPos.ZERO));
            if(material==PlanetMaterial.SUN_PLASMA)properties.lightLevel(state->15).emissiveRendering(state->true);
            if(material==PlanetMaterial.MAGMA)properties.lightLevel(state->3);
            if(material==PlanetMaterial.PACKED_ICE || material==PlanetMaterial.BLUE_ICE)properties.friction(.98f);
            var block=new Block(properties.setId(key));Registry.register(BuiltInRegistries.BLOCK,key,block);
            Registry.register(BuiltInRegistries.ITEM,id,new BlockItem(block,new Item.Properties().setId(ResourceKey.create(Registries.ITEM,id))));
            BLOCKS.put(material,block);MATERIALS.put(block,material);
        }
    }
    public static Block surface(PlanetMaterial m) {return BLOCKS.getOrDefault(m,PlanetChunkGenerator.materialState(m).getBlock());}
    public static Block plasma() {return BLOCKS.get(PlanetMaterial.SUN_PLASMA);}
    public static PlanetMaterial material(Block block) {return MATERIALS.get(block);}
}
