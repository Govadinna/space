package dev.seamlessspace.gametest;

import dev.seamlessspace.client.SeamlessSpaceClient;
import dev.seamlessspace.client.SpaceRenderer;
import dev.seamlessspace.flight.SpaceFlight;
import dev.seamlessspace.universe.DVec3;
import dev.seamlessspace.universe.Universe;
import dev.seamlessspace.world.PlanetWorlds;
import dev.seamlessspace.world.OrbitalSurfaceCache;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Full client/server handoff; shortened orbital travel is only a test fixture. */
public final class PlanetClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        if(Boolean.getBoolean("seamlessspace.fullGallery")){runGallery(context);return;}
        try(var world=context.worldBuilder().adjustSettings(settings->{settings.setSeed("7042026");settings.setGenerateStructures(false);}).create()) {
            world.getServer().runCommand("gamemode creative @a");
            world.getServer().runCommand("tp @a 0 2040 0");
            context.waitFor(client->client.player!=null && client.player.isCreative() && client.player.getY()>2030 && client.gui.screen()==null);
            context.runOnClient(client->{client.player.getAbilities().flying=true;client.player.onUpdateAbilities();client.options.keyJump.setDown(true);});
            context.waitFor(client->SeamlessSpaceClient.isActive(),400);
            context.waitTicks(100);
            context.runOnClient(client->{
                client.options.keyJump.setDown(false);
                var flight=(SpaceFlight)field(controller(),"flight");
                if(!client.level.dimension().equals(Level.OVERWORLD))throw new AssertionError("Atmosphere crossing must retain the native Overworld player");
                if(!client.player.isCreative() || !client.player.getAbilities().flying)throw new AssertionError("Creative flight lost at atmosphere boundary");
                if(flight.altitude()<3000)throw new AssertionError("Held Space stalled at 2000: altitude="+flight.altitude());
            });
            var before=context.computeOnClient(client->((SpaceFlight)field(controller(),"flight")).position());
            context.runOnClient(client->{client.options.keyUp.setDown(true);client.options.keyRight.setDown(true);});
            context.waitTicks(60);
            context.runOnClient(client->{client.options.keyUp.setDown(false);client.options.keyRight.setDown(false);
                if(((SpaceFlight)field(controller(),"flight")).position().distance(before)<100)throw new AssertionError("Creative horizontal movement stalled");
            });
            var rightStart=context.computeOnClient(client->((SpaceFlight)field(controller(),"flight")).position());
            var cameraRight=context.computeOnClient(client->((SpaceFlight)field(controller(),"flight")).right());
            context.runOnClient(client->client.options.keyRight.setDown(true));context.waitTicks(20);
            context.runOnClient(client->{client.options.keyRight.setDown(false);
                if(((SpaceFlight)field(controller(),"flight")).position().subtract(rightStart).dot(cameraRight)<10)throw new AssertionError("D inverted relative to camera");
            });
            // No screenshots in the routine movement regression.
        }
    }
    private void runGallery(ClientGameTestContext context) {
        var visibleLoadingScreens=new java.util.concurrent.atomic.AtomicInteger();
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client->{
            if(SeamlessSpaceClient.isTravelPending() && client.gui.screen() instanceof net.minecraft.client.gui.screens.LevelLoadingScreen)visibleLoadingScreens.incrementAndGet();
        });
        int suppressedBefore=SeamlessSpaceClient.suppressedLoadingScreens();
        try (var world = context.worldBuilder().adjustSettings(settings -> {
            settings.setSeed("7042026");
            settings.setWorldType(settings.getNormalPresetList().stream().filter(p -> p.preset()!=null && p.preset().is(net.minecraft.world.level.levelgen.presets.WorldPresets.NORMAL)).findFirst().orElseThrow());
            settings.setGenerateStructures(false);
        }).create()) {
            world.getServer().runCommand("gamemode creative @a");
            context.waitFor(client -> client.player != null && client.player.isCreative() && client.gui.screen() == null);
            context.waitFor(client -> dev.seamlessspace.world.EarthPreview.image(client.getSingleplayerServer())!=null,2400);
            context.runOnClient(client -> invoke(controller(), "launch"));
            context.waitFor(client -> SeamlessSpaceClient.isActive() && client.level.dimension().equals(PlanetWorlds.SPACE) && !SeamlessSpaceClient.isTravelPending());
            context.waitTicks(10);
            context.takeScreenshot("space-11-orbit");
            var nativeBefore=context.computeOnClient(client->client.player.position());
            var orbitalBefore=context.computeOnClient(client->((SpaceFlight)field(controller(),"flight")).position());
            context.runOnClient(client->{
                if(!client.player.isCreative() || !client.player.getAbilities().flying || client.player.isNoGravity())throw new AssertionError("Orbital travel must use ordinary creative abilities");
                client.options.keyUp.setDown(true);
            });
            context.waitTicks(20);context.runOnClient(client->client.options.keyUp.setDown(false));context.waitTicks(8);
            context.runOnClient(client->{
                if(client.player.position().distanceTo(nativeBefore)<1)throw new AssertionError("Real creative player must move, not stay pinned at an anchor");
                if(((SpaceFlight)field(controller(),"flight")).position().distance(orbitalBefore)<20)throw new AssertionError("Native creative movement must advance orbital coordinates");
            });
            // Empty terrain passes may be omitted: movement must remain live for sustained flight.
            var sustainedStart=context.computeOnClient(client->((SpaceFlight)field(controller(),"flight")).position());
            context.runOnClient(client->{client.options.keyRight.setDown(true);client.options.keyJump.setDown(true);});
            context.waitTicks(80);
            context.runOnClient(client->{
                client.options.keyRight.setDown(false);client.options.keyJump.setDown(false);
                if(((SpaceFlight)field(controller(),"flight")).position().distance(sustainedStart)<100)
                    throw new AssertionError("Sustained strafe/ascent stalled in empty space");
            });
            var detailWait=new java.util.concurrent.atomic.AtomicInteger();
            context.waitFor(client -> {
                var patch=dev.seamlessspace.world.EarthPreview.detail(client.getSingleplayerServer());
                if(detailWait.incrementAndGet()%400==0)SeamlessSpaceClient.LOGGER.info("Earth QA detail={} altitude={}",patch==null?"none":patch.image().size()+"/"+patch.span(),((SpaceFlight)field(controller(),"flight")).altitude());
                return patch!=null && patch.image().size()>=512;
            },2400);
            context.runOnClient(client->{var flight=(SpaceFlight)field(controller(),"flight");flight.engage(flight.position(),new DVec3(0,-1,0));});
            context.waitTicks(10);context.takeScreenshot("space-11-earth-local-detail");
            context.runOnClient(client -> client.gui.hud.toggle());
            var moon = context.computeOnClient(client -> ((Universe) field(controller(), "universe")).homeSystem().get(1));
            context.runOnClient(client -> {
                Object controller = controller();
                Universe universe = (Universe) field(controller, "universe");
                SpaceFlight flight = (SpaceFlight) field(controller, "flight");
                DVec3 eye = moon.center().add(DVec3.UP.multiply(universe.surfaceRadius(moon, DVec3.UP) + 30_000 + SpaceFlight.EYE_HEIGHT));
                flight.engage(eye, new DVec3(0, -0.15, 1));
            });
            context.waitFor(client -> !OrbitalSurfaceCache.tiles(client.getSingleplayerServer(),moon).isEmpty(),2400);
            context.runOnClient(client -> {
                if (!client.level.dimension().equals(PlanetWorlds.SPACE)) throw new AssertionError("Preloading must keep the real player in space");
            });
            context.waitTicks(40);
            context.runOnClient(client->((dev.seamlessspace.client.SpaceConfig)field(controller(),"config")).automaticLanding=false);
            context.takeScreenshot("space-11-preloaded-approach");
            context.runOnClient(client -> {
                Universe universe=(Universe)field(controller(),"universe");
                SpaceFlight flight=(SpaceFlight)field(controller(),"flight");
                flight.engage(moon.center().add(DVec3.UP.multiply(universe.surfaceRadius(moon,DVec3.UP)+700+SpaceFlight.EYE_HEIGHT)),new DVec3(0,-.35,1));
            });
            context.waitTicks(35);
            context.takeScreenshot("space-11-voxel-approach");
            context.runOnClient(client -> {
                ((dev.seamlessspace.client.SpaceConfig)field(controller(),"config")).automaticLanding=false;
                Universe universe = (Universe)field(controller(),"universe");
                SpaceFlight flight = (SpaceFlight)field(controller(),"flight");
                flight.engage(moon.center().add(DVec3.UP.multiply(universe.surfaceRadius(moon,DVec3.UP)+50+SpaceFlight.EYE_HEIGHT)),new DVec3(0,-.6,1));
            });
            context.waitTicks(30);
            context.takeScreenshot("space-11-before-chunks");
            float approachFov=context.computeOnClient(client -> client.gameRenderer.mainCamera().getFov());
            context.runOnClient(client -> ((dev.seamlessspace.client.SpaceConfig)field(controller(),"config")).automaticLanding=true);
            context.waitFor(client -> client.level != null && client.level.dimension().equals(PlanetWorlds.dimensionKey(moon))
                    && !SeamlessSpaceClient.isActive() && client.gui.screen() == null, 2400);
            context.runOnClient(client -> {
                if (!client.player.isCreative() || client.player.isNoGravity()) throw new AssertionError("Client must resume interactive creative physics");
                if (!client.player.getAbilities().flying) throw new AssertionError("Arrival above ground must keep creative flight enabled");
                int ground = client.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,0,0);
                if (client.player.getY() < ground + 40) throw new AssertionError("Approach must preserve orbital height instead of snapping to ground");
            });
            context.waitTicks(10);
            context.runOnClient(client -> {
                if(Math.abs(client.gameRenderer.mainCamera().getFov()-approachFov)>.05)
                    throw new AssertionError("Landing must not rescale the image through creative-flight FOV changes");
                var position=dev.seamlessspace.world.PlanetSurface.orbitalEye(moon,
                        new DVec3(client.player.getX(),client.player.getY(),client.player.getZ()),client.player.getEyeHeight());
                var flight=(SpaceFlight)field(controller(),"flight");
                if(position.distance(flight.position())>.1) throw new AssertionError("Handoff must preserve eye coordinates to within 10 cm");
                var expected=flight.forward();
                var actual=client.player.getLookAngle();
                if(expected.dot(new DVec3(actual.x,actual.y,actual.z))<.9999) throw new AssertionError("Handoff must preserve camera orientation");
            });
            context.takeScreenshot("space-11-block-surface");
            context.runOnClient(client -> {
                if(client.level.getMaxY()!=2031) throw new AssertionError("Client must receive the expanded build ceiling");
            });
            world.getServer().runOnServer(server -> {
                var level=server.getLevel(PlanetWorlds.dimensionKey(moon));
                level.setBlock(new BlockPos(12,2000,12),Blocks.GOLD_BLOCK.defaultBlockState(),3);
                level.setBlock(new BlockPos(13,2031,12),Blocks.DIAMOND_BLOCK.defaultBlockState(),3);
            });
            context.waitFor(client -> client.level.getBlockState(new BlockPos(12,2000,12)).is(Blocks.GOLD_BLOCK)
                    && client.level.getBlockState(new BlockPos(13,2031,12)).is(Blocks.DIAMOND_BLOCK));
            var caveReturn=context.computeOnClient(client->new double[]{client.player.getX(),client.player.getY(),client.player.getZ(),client.player.getYRot(),client.player.getXRot()});
            world.getServer().runOnServer(server->{
                var level=server.getLevel(PlanetWorlds.dimensionKey(moon));level.getChunk(5,14);level.getChunk(6,14);
                var cave=dev.seamlessspace.world.PlanetGeology.cavity(moon,80,225,dev.seamlessspace.world.PlanetSurface.height(moon,80,225));
                var player=server.getPlayerList().getPlayers().getFirst();player.teleportTo(level,80.5,cave.minY()+1,225.5,java.util.Set.of(),-90,0,true);
            });
            context.waitFor(client->client.level.getChunkSource().hasChunk(5,14) && Math.abs(client.player.getX()-80.5)<1,1200);
            context.waitTicks(20);context.takeScreenshot("space-11-lunar-tube-interior");
            world.getServer().runOnServer(server->{var player=server.getPlayerList().getPlayers().getFirst();player.teleportTo(player.level(),96.5,dev.seamlessspace.world.PlanetSurface.standingHeight(moon,96,225)+30,225.5,java.util.Set.of(),0,80,true);});
            context.waitTicks(20);context.takeScreenshot("space-11-lunar-tube-entrance");
            world.getServer().runOnServer(server->{var player=server.getPlayerList().getPlayers().getFirst();player.teleportTo(player.level(),caveReturn[0],caveReturn[1],caveReturn[2],java.util.Set.of(),(float)caveReturn[3],(float)caveReturn[4],true);});
            context.waitTicks(20);
            context.runOnClient(client -> client.gui.hud.toggle());
            BlockPos build = context.computeOnClient(client -> client.player.blockPosition().offset(2, 1, 0));
            world.getServer().runOnServer(server -> server.getLevel(PlanetWorlds.dimensionKey(moon))
                    .setBlock(build, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3));
            context.waitFor(client -> client.level.getBlockState(build).is(Blocks.DIAMOND_BLOCK));
            world.getServer().runOnServer(server -> OrbitalSurfaceCache.capture(server,
                    server.getLevel(PlanetWorlds.dimensionKey(moon)),build.getX()>>4,build.getZ()>>4));
            context.runOnClient(client -> {
                var tile = OrbitalSurfaceCache.tiles(client.getSingleplayerServer(),moon).stream()
                        .filter(t -> t.chunkX==(build.getX()>>4) && t.chunkZ==(build.getZ()>>4)).findFirst().orElseThrow();
                if(tile.height(build.getX()&15,build.getZ()&15)!=build.getY()+1) throw new AssertionError("Orbital LOD must include saved buildings");
            });
            world.getServer().runCommand("item replace entity @a weapon.mainhand with minecraft:spyglass");
            context.waitFor(client -> client.player.getMainHandItem().is(Items.SPYGLASS));
            context.runOnClient(client -> client.options.keyUse.setDown(true));
            context.waitTicks(20);
            float standardZoom = context.computeOnClient(client -> client.gameRenderer.mainCamera().getFov());
            context.runOnClient(client -> {
                if(!SeamlessSpaceClient.scrollTelescope(1)) throw new AssertionError("Spyglass wheel must be handled");
            });
            context.waitTicks(10);
            context.runOnClient(client -> {
                if(client.gameRenderer.mainCamera().getFov() > standardZoom*.5) throw new AssertionError("Telescope must change actual camera projection");
                client.options.keyUse.setDown(false);
                client.player.stopUsingItem();
            });
            context.runOnClient(client -> invoke(controller(), "launch"));
            context.waitFor(client -> SeamlessSpaceClient.isActive());
            world.getServer().runOnServer(server -> {
                var level=server.getLevel(PlanetWorlds.dimensionKey(moon));
                for(int z=-32;z<32;z++) for(int x=-32;x<32;x++)
                    level.setBlock(new BlockPos(x,280,z),Blocks.DIAMOND_BLOCK.defaultBlockState(),0);
                OrbitalSurfaceCache.captureRegion(server,level,0,0,3);
            });
            context.runOnClient(client -> {
                Universe universe=(Universe)field(controller(),"universe");
                SpaceFlight flight=(SpaceFlight)field(controller(),"flight");
                flight.engage(moon.center().add(DVec3.UP.multiply(universe.surfaceRadius(moon,DVec3.UP)+10000+SpaceFlight.EYE_HEIGHT)),new DVec3(0,-1,0));
                client.options.keyUse.setDown(true);
            });
            context.waitTicks(60);
            context.runOnClient(client -> {
                if(((SpaceRenderer)field(controller(),"renderer")).hasFailed()) throw new AssertionError("Orbital LOD must render successfully");
            });
            context.takeScreenshot("space-11-real-building-from-orbit");
            context.runOnClient(client -> {
                client.options.keyUse.setDown(false);
                client.player.stopUsingItem();
                invoke(controller(),"returnHome");
            });
            context.waitFor(client -> client.level != null && client.level.dimension().equals(Level.OVERWORLD)
                    && !SeamlessSpaceClient.isActive() && client.gui.screen() == null, 2400);
            context.runOnClient(client -> {invoke(controller(),"launch");client.gui.hud.toggle();});
            for(var body:new dev.seamlessspace.universe.Planet.Body[]{dev.seamlessspace.universe.Planet.Body.EARTH,
                    dev.seamlessspace.universe.Planet.Body.MOON,dev.seamlessspace.universe.Planet.Body.MARS,
                    dev.seamlessspace.universe.Planet.Body.JUPITER,dev.seamlessspace.universe.Planet.Body.SATURN,
                    dev.seamlessspace.universe.Planet.Body.URANUS,dev.seamlessspace.universe.Planet.Body.NEPTUNE,
                    dev.seamlessspace.universe.Planet.Body.SUN}) {
                context.runOnClient(client -> {
                    var u=(Universe)field(controller(),"universe");var planet=u.homeSystem().stream().filter(p->p.body()==body).findFirst().orElseThrow();
                    var direction=u.sun().center().subtract(planet.center()).normalized();
                    if(body==dev.seamlessspace.universe.Planet.Body.SUN)direction=new DVec3(0,.2,1).normalized();
                    var eye=planet.center().add(direction.multiply(planet.radius()*3.2));
                    ((SpaceFlight)field(controller(),"flight")).engage(eye,planet.center().subtract(eye));
                });
                context.waitTicks(20);context.takeScreenshot("space-11-"+body.name().toLowerCase()+"-orbit");
            }
            context.runOnClient(client -> {
                var u=(Universe)field(controller(),"universe");var planet=u.homeSystem().stream().filter(p->p.body()==dev.seamlessspace.universe.Planet.Body.SATURN).findFirst().orElseThrow();
                var n=planet.ringNormal();var east=new DVec3(0,0,1).cross(n).normalized();
                var eye=planet.center().add(east.multiply(planet.radius()*1.7)).add(n.multiply(10));
                ((SpaceFlight)field(controller(),"flight")).engage(eye,east.negate().add(n.multiply(-.12)));
            });
            context.waitTicks(30);
            context.runOnClient(client -> {if(((SpaceRenderer)field(controller(),"renderer")).ringFragments()<100)throw new AssertionError("Close rings must resolve to individual block instances");});
            context.takeScreenshot("space-11-saturn-ring-blocks");
            context.runOnClient(client -> {
                var u=(Universe)field(controller(),"universe");var mars=u.homeSystem().stream().filter(p->p.body()==dev.seamlessspace.universe.Planet.Body.MARS).findFirst().orElseThrow();
                ((SpaceFlight)field(controller(),"flight")).engage(mars.center().add(DVec3.UP.multiply(u.surfaceRadius(mars,DVec3.UP)+50+SpaceFlight.EYE_HEIGHT)),new DVec3(0,-.1,1));
            });
            context.waitFor(client -> !SeamlessSpaceClient.isActive() && client.gui.screen()==null,2400);
            context.waitTicks(15);context.takeScreenshot("space-11-mars-red-sky");
            context.runOnClient(client -> {invoke(controller(),"launch");
                var u=(Universe)field(controller(),"universe");var sun=u.sun();
                ((SpaceFlight)field(controller(),"flight")).engage(sun.center().add(DVec3.UP.multiply(u.surfaceRadius(sun,DVec3.UP)+50+SpaceFlight.EYE_HEIGHT)),new DVec3(0,-.2,1));
            });
            context.waitFor(client -> !SeamlessSpaceClient.isActive() && client.gui.screen()==null,2400);
            context.waitTicks(20);
            context.runOnClient(client -> {
                if(((SpaceRenderer)field(controller(),"renderer")).solarFlareVertices()>18000)throw new AssertionError("Solar geometry exceeded its fixed budget");
                if(((SpaceRenderer)field(controller(),"renderer")).solarFlareVertices()<1000)throw new AssertionError("Sun surface must render real 3D plasma arches");
                if(((SpaceRenderer)field(controller(),"renderer")).hasFailed())throw new AssertionError("All new shaders must render");
            });
            context.takeScreenshot("space-11-sun-blocks");
            world.getServer().runOnServer(server->{
                var player=server.getPlayerList().getPlayers().getFirst();var sun=dev.seamlessspace.world.PlanetWorlds.planetForLevel(server,player.level().dimension());
                player.teleportTo(player.level(),300.5,dev.seamlessspace.world.PlanetSurface.standingHeight(sun,300,0)+5,.5,java.util.Set.of(),-35,20,true);
            });
            context.waitTicks(20);
            context.runOnClient(client->{if(SeamlessSpaceClient.currentSurfacePlanet().body()!=dev.seamlessspace.universe.Planet.Body.SUN)throw new AssertionError("Plasma close-up must stay in the solar dimension");});
            context.takeScreenshot("space-11-solar-plasma-close");
            context.runOnClient(client->{invoke(controller(),"launch");
                var u=(Universe)field(controller(),"universe");var gas=u.homeSystem().stream().filter(p->p.body()==dev.seamlessspace.universe.Planet.Body.JUPITER).findFirst().orElseThrow();
                ((SpaceFlight)field(controller(),"flight")).engage(gas.center().add(DVec3.UP.multiply(u.surfaceRadius(gas,DVec3.UP)+50+SpaceFlight.EYE_HEIGHT)),new DVec3(0,-.1,1));
            });
            context.waitFor(client -> !SeamlessSpaceClient.isActive() && client.gui.screen()==null,2400);
            context.waitTicks(20);context.takeScreenshot("space-11-jupiter-gas-haze");
            context.runOnClient(client -> {client.gui.hud.toggle();invoke(controller(),"returnHome");});
            context.waitFor(client -> client.level.dimension().equals(Level.OVERWORLD) && !SeamlessSpaceClient.isActive() && client.gui.screen()==null,2400);
            context.runOnClient(client -> {
                if(visibleLoadingScreens.get()!=0)throw new AssertionError("A loading screen was visible during planetary travel");
                if(SeamlessSpaceClient.suppressedLoadingScreens()<=suppressedBefore)throw new AssertionError("Prepared travel must bypass native loading GUI");
            });
            world.getServer().runCommand("tp @a 0 1000 0");
            context.waitTicks(20);
            context.runOnClient(client -> {
                if(SeamlessSpaceClient.isActive()) throw new AssertionError("Below 2 km must remain in native creative flight");
            });
            world.getServer().runCommand("tp @a 0 2070 0");
            context.waitFor(client -> SeamlessSpaceClient.isActive(),200);
            context.runOnClient(client -> invoke(controller(),"returnHome"));
            context.waitFor(client -> !SeamlessSpaceClient.isActive() && client.gui.screen()==null,2400);
            world.getServer().runOnServer(server->{
                var level=server.getLevel(PlanetWorlds.dimensionKey(moon));
                for(int z=-32;z<32;z++)for(int x=-32;x<32;x++)level.setBlock(new BlockPos(x,280,z),Blocks.AIR.defaultBlockState(),0);
                level.setBlock(new BlockPos(12,2000,12),Blocks.AIR.defaultBlockState(),0);
                level.setBlock(new BlockPos(13,2031,12),Blocks.AIR.defaultBlockState(),0);level.setBlock(build,Blocks.AIR.defaultBlockState(),0);
                OrbitalSurfaceCache.captureRegion(server,level,0,0,4);
            });
            context.runOnClient(client->{invoke(controller(),"launch");((dev.seamlessspace.client.SpaceConfig)field(controller(),"config")).automaticLanding=false;client.gui.hud.toggle();});
            for(var body:dev.seamlessspace.universe.Planet.Body.values()) {
                if(body==dev.seamlessspace.universe.Planet.Body.GENERIC || body==dev.seamlessspace.universe.Planet.Body.EARTH)continue;
                context.runOnClient(client->{
                    var u=(Universe)field(controller(),"universe");var p=u.homeSystem().stream().filter(q->q.body()==body).findFirst().orElseThrow();
                    var local=dev.seamlessspace.world.PlanetSurface.frame(p,0,0);
                    var feet=new DVec3(0,dev.seamlessspace.world.PlanetSurface.standingHeight(p,0,0)+(body==dev.seamlessspace.universe.Planet.Body.SATURN?1200:450),0);
                    var eye=dev.seamlessspace.world.PlanetSurface.orbitalEye(p,feet,SpaceFlight.EYE_HEIGHT);
                    ((SpaceFlight)field(controller(),"flight")).engage(eye,local.radial().negate().add(local.north().multiply(.10)));
                });
                context.waitTicks(60);
                context.runOnClient(client->{if(((SpaceRenderer)field(controller(),"renderer")).hasFailed())throw new AssertionError("Geology shader failed");});
                context.takeScreenshot("space-11-geology-"+body.name().toLowerCase());
            }
            // Low oblique views reveal silhouettes and ledges hidden by the top-down gallery.
            for(var body:new dev.seamlessspace.universe.Planet.Body[]{dev.seamlessspace.universe.Planet.Body.NEPTUNE,dev.seamlessspace.universe.Planet.Body.MARS,dev.seamlessspace.universe.Planet.Body.SUN}) {
                context.runOnClient(client->{
                    var u=(Universe)field(controller(),"universe");var p=u.homeSystem().stream().filter(q->q.body()==body).findFirst().orElseThrow();
                    int x=body==dev.seamlessspace.universe.Planet.Body.NEPTUNE?900:body==dev.seamlessspace.universe.Planet.Body.MARS?2400:0;
                    int z=body==dev.seamlessspace.universe.Planet.Body.NEPTUNE?900:body==dev.seamlessspace.universe.Planet.Body.MARS?2400:0;
                    var basis=dev.seamlessspace.world.PlanetSurface.frame(p,x,z);
                    var feet=new DVec3(x,dev.seamlessspace.world.PlanetSurface.standingHeight(p,x,z)+(body==dev.seamlessspace.universe.Planet.Body.SUN?240:80),z);
                    var eye=dev.seamlessspace.world.PlanetSurface.orbitalEye(p,feet,SpaceFlight.EYE_HEIGHT);
                    ((SpaceFlight)field(controller(),"flight")).engage(eye,basis.north().add(basis.radial().multiply(-.32)));
                });
                context.waitTicks(100);context.takeScreenshot("space-11-oblique-"+body.name().toLowerCase());
            }
            for(int sign:new int[]{-1,1}) {
                final int pole=sign;
                context.runOnClient(client->{
                    var u=(Universe)field(controller(),"universe");var p=u.home();var radial=new DVec3(0,0,pole);
                    ((SpaceFlight)field(controller(),"flight")).engage(p.center().add(radial.multiply(p.radius()+5000)),radial.negate());
                });
                context.waitTicks(80);context.takeScreenshot("space-11-earth-pole-"+sign);
            }
            for(int sign:new int[]{-1,1}) {
                final int pole=sign;
                context.runOnClient(client->{
                    var u=(Universe)field(controller(),"universe");var p=u.homeSystem().get(1);var radial=new DVec3(0,0,pole);
                    ((SpaceFlight)field(controller(),"flight")).engage(p.center().add(radial.multiply(p.radius()+1500)),radial.negate());
                });
                context.waitTicks(80);context.takeScreenshot("space-11-moon-pole-"+sign);
            }
            context.runOnClient(client->{
                ((dev.seamlessspace.client.SpaceConfig)field(controller(),"config")).automaticLanding=true;
                var u=(Universe)field(controller(),"universe");var p=u.homeSystem().stream().filter(q->q.body()==dev.seamlessspace.universe.Planet.Body.NEPTUNE).findFirst().orElseThrow();
                var basis=dev.seamlessspace.world.PlanetSurface.frame(p,900,900);
                var eye=dev.seamlessspace.world.PlanetSurface.orbitalEye(p,new DVec3(900,dev.seamlessspace.world.PlanetSurface.standingHeight(p,900,900)+20,900),SpaceFlight.EYE_HEIGHT);
                ((SpaceFlight)field(controller(),"flight")).engage(eye,basis.north().add(basis.radial().multiply(-.12)));
            });
            context.waitFor(client->!SeamlessSpaceClient.isActive() && client.gui.screen()==null,2400);
            context.runOnClient(client->{if(SeamlessSpaceClient.currentSurfacePlanet().body()!=dev.seamlessspace.universe.Planet.Body.NEPTUNE)throw new AssertionError("Crystal close-up must use real native chunks");});
            context.waitTicks(30);context.takeScreenshot("space-11-native-crystals");
            context.runOnClient(client->{invoke(controller(),"launch");
                ((dev.seamlessspace.client.SpaceConfig)field(controller(),"config")).automaticLanding=false;
                var u=(Universe)field(controller(),"universe");var p=u.home();var basis=dev.seamlessspace.world.PlanetSurface.frame(p,800,600);
                ((SpaceFlight)field(controller(),"flight")).engage(dev.seamlessspace.world.PlanetSurface.orbitalEye(p,new DVec3(800,700,600),SpaceFlight.EYE_HEIGHT),basis.radial().negate().add(basis.north().multiply(.1)));
            });
            context.waitFor(client->client.level.dimension().equals(PlanetWorlds.SPACE) && !SeamlessSpaceClient.isTravelPending(),2400);
            context.waitTicks(60);context.takeScreenshot("space-11-earth-before-landing");
            var earthEye=context.computeOnClient(client->((SpaceFlight)field(controller(),"flight")).position());
            context.runOnClient(client->((dev.seamlessspace.client.SpaceConfig)field(controller(),"config")).automaticLanding=true);
            context.waitFor(client->!SeamlessSpaceClient.isActive() && client.level.dimension().equals(Level.OVERWORLD) && client.gui.screen()==null,2400);
            context.runOnClient(client->{
                var u=(Universe)field(controller(),"universe");var eye=dev.seamlessspace.world.PlanetSurface.orbitalEye(u.home(),new DVec3(client.player.getX(),client.player.getY(),client.player.getZ()),client.player.getEyeHeight());
                if(eye.distance(earthEye)>.1)throw new AssertionError("Earth landing must preserve camera position to 10cm");
                if(visibleLoadingScreens.get()!=0)throw new AssertionError("Native creative travel must not expose a loading GUI");
            });
            context.waitTicks(20);context.takeScreenshot("space-11-earth-after-landing");
            context.waitFor(client->{var map=dev.seamlessspace.world.EarthPreview.image(client.getSingleplayerServer());return map!=null && map.size()==2048;},6000);
            context.runOnClient(client->{invoke(controller(),"launch");((dev.seamlessspace.client.SpaceConfig)field(controller(),"config")).automaticLanding=false;
                var u=(Universe)field(controller(),"universe");var p=u.home();var direction=u.sun().center().subtract(p.center()).normalized();
                ((SpaceFlight)field(controller(),"flight")).engage(p.center().add(direction.multiply(p.radius()*3.2)),direction.negate());
            });
            context.waitFor(client->client.level.dimension().equals(PlanetWorlds.SPACE) && !SeamlessSpaceClient.isTravelPending(),2400);
            context.waitTicks(80);context.takeScreenshot("space-11-earth-global-2048");
            context.runOnClient(client->invoke(controller(),"returnHome"));
            context.waitFor(client->!SeamlessSpaceClient.isActive() && client.gui.screen()==null,2400);
        }
    }

    private static Object controller() { return field(null, "instance"); }
    private static Object field(Object target, String name) {
        try {
            Field field = SeamlessSpaceClient.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static void invoke(Object target, String name) {
        try {
            Method method = target.getClass().getDeclaredMethod(name);
            method.setAccessible(true);
            method.invoke(target);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
