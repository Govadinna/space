package dev.seamlessspace.world;

/** Stable LOD material IDs; textures are resolved from Minecraft's active block atlas. */
public enum PlanetMaterial {
    COLOR(0x808080,"stone"), STONE(0x707070,"stone"), ANDESITE(0x808080,"andesite"),
    DIORITE(0xd1d1d1,"diorite"), GRAVEL(0x77736e,"gravel"), RED_SAND(0xd87f33,"red_sand"),
    RED_SANDSTONE(0xba632d,"red_sandstone"), TERRACOTTA(0x985e43,"terracotta"),
    ORANGE_TERRACOTTA(0x9f5224,"orange_terracotta"), SNOW(0xeeeeee,"snow"),
    PACKED_ICE(0x8ea6d8,"packed_ice"), BLUE_ICE(0x7facfa,"blue_ice"),
    BASALT(0x4c4c4c,"basalt_top"), BLACKSTONE(0x28232d,"blackstone"),
    GRASS(0x7fb238,"grass_block_top"), DIRT(0x976d4d,"dirt"), SAND(0xd8ce9e,"sand"),
    WATER(0x365eb8,"water_still"), LAVA(0xff6811,"lava_still"),
    DIAMOND(0x5cdbd5,"diamond_block"), GOLD(0xfaee4d,"gold_block"),
    IRON(0xa7a7a7,"iron_block"), NETHERITE(0x443b3b,"netherite_block"),
    PLANKS(0x8f7748,"oak_planks"), COBBLESTONE(0x707070,"cobblestone"),
    BRICKS(0x985e43,"bricks"), WHITE_CONCRETE(0xd1d6d8,"white_concrete"), SUN_PLASMA(0xff8010,"seamlessspace:solar_plasma"),
    TUFF(0x70736a,"tuff"), CALCITE(0xdad9cf,"calcite"), MUD(0x57443d,"mud"), MAGMA(0xbf4b17,"magma");
    public final int rgb;
    public final String texture;
    PlanetMaterial(int rgb,String texture) { this.rgb=rgb; this.texture=texture; }
    public PlanetMaterial side() {
        return switch(this) { case GRASS -> DIRT; case RED_SAND -> RED_SANDSTONE; case SNOW -> PACKED_ICE; default -> this; };
    }
}
