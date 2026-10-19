package dev.seamlessspace.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.seamlessspace.flight.SpaceFlight;
import dev.seamlessspace.universe.DVec3;
import dev.seamlessspace.universe.Planet;
import dev.seamlessspace.universe.Universe;
import dev.seamlessspace.world.PlanetSurface;
import dev.seamlessspace.world.PlanetAtmosphere;
import dev.seamlessspace.world.PlanetWorlds;
import dev.seamlessspace.world.OrbitalSurfaceCache;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/** Space flight ends at real server terrain; vanilla owns all surface interactions. */
public final class SeamlessSpaceClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("seamlessspace");
    private static SeamlessSpaceClient instance;
    private SpaceConfig config;
    private Universe universe;
    private SpaceFlight flight;
    private final SpaceRenderer renderer = new SpaceRenderer();
    private KeyMapping launchKey, returnKey, landKey, targetKey, brakeKey, fasterKey, slowerKey;
    private CompletableFuture<Void> departure;
    private DVec3 nativeEye;
    private double speedMultiplier=1;
    private boolean returnAfterDeparture;
    private float appliedCreativeSpeed=-1;
    private volatile Anchor anchor, serverAnchor;
    private volatile PendingTravel pending;
    private Frame frame, groundFrame;
    private Approach approach;
    private final java.util.concurrent.atomic.AtomicInteger approachJobs=new java.util.concurrent.atomic.AtomicInteger();
    private long nextApproachCheck;
    private ReturnPoint homeReturn;
    private int targetIndex = 1;
    private double telescopeZoom = 10;
    private boolean renderFailed;
    private boolean automaticEntryArmed = true;
    private net.minecraft.server.MinecraftServer catalogServer;

    @Override
    public void onInitializeClient() {
        instance = this;
        config = SpaceConfig.load();
        renderer.setResolutionHeight(config.renderHeight);
        renderer.setStarBrightness(config.starBrightness);
        renderer.setStarSize(config.starSize);
        universe = new Universe(config.seed, config.scale,config.compactSystem,config.solarSystem);
        universe.setHomeHeightSampler(direction -> dev.seamlessspace.world.EarthPreview.heightOffset(Minecraft.getInstance().getSingleplayerServer(),universe.home(),direction));
        renderer.setUniverse(universe);
        flight = new SpaceFlight(universe, config.maxCruiseSpeed);
        var category = KeyMapping.Category.register(id("space"));
        launchKey = bind("launch", InputConstants.KEY_F7, category);
        returnKey = bind("return", InputConstants.KEY_F6, category);
        landKey = bind("walk", InputConstants.KEY_G, category);
        targetKey = bind("target", InputConstants.KEY_N, category);
        brakeKey = bind("brake", InputConstants.KEY_B, category);
        fasterKey=bind("faster",InputConstants.KEY_EQUALS,category);
        slowerKey=bind("slower",InputConstants.KEY_MINUS,category);
        ClientTickEvents.END_CLIENT_TICK.register(this::clientTick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            Anchor current = serverAnchor;
            if (current == null && pending != null) current = pending.source;
            if (current != null) {
                var player = server.getPlayerList().getPlayer(current.player());
                if (player != null && player.level().dimension().equals(current.dimension())) {
                    player.setNoGravity(current.noGravity());
                    DVec3 pos = current.returnPoint().position();
                    var original=server.getLevel(current.returnPoint().dimension());
                    if(original!=null)player.teleportTo(original,pos.x(),pos.y(),pos.z(),Set.of(),player.getYRot(),player.getXRot(),true);
                    player.setDeltaMovement(Vec3.ZERO);
                }
            }
            anchor = null; serverAnchor = null;
        });
        LevelExtractionEvents.END_EXTRACTION.register(context -> extractFrame());
        HudElementRegistry.addLast(id("navigation"), (graphics, delta) -> drawHud(graphics));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> renderer.close());
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> dispatcher.register(literal("space")
                .executes(ctx -> { message("/space launch · /space land · /space return · /space planets · /space target <номер>"); return 1; })
                .then(literal("speed").then(argument("multiplier",com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(.125,100000000))
                    .executes(ctx->{setSpeed(com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx,"multiplier"));return 1;})))
                .then(literal("launch").executes(ctx -> { launch(); return 1; }))
                .then(literal("land").executes(ctx -> { requestLanding(true); return 1; }))
                .then(literal("return").executes(ctx -> { returnHome(); return 1; }))
                .then(literal("landmarks").executes(ctx -> { listLandmarks(); return 1; }))
                .then(literal("planets").executes(ctx -> { listPlanets(); return 1; }))
                .then(literal("target").then(argument("number", IntegerArgumentType.integer(1))
                        .executes(ctx -> {
                            int selected=IntegerArgumentType.getInteger(ctx,"number")-1;
                            if(selected>=universe.targets().size()) {message("Нет такой цели. Список: /space planets");return 0;}
                            targetIndex=selected;aimTarget();return 1;
                        })))));
    }

    private static KeyMapping bind(String name, int key, KeyMapping.Category category) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.seamlessspace." + name, key, category));
    }
    public static Identifier id(String path) { return Identifier.fromNamespaceAndPath("seamlessspace", path); }
    private static int suppressedLoadingScreens;
    public static boolean isTravelPending(){return instance!=null && (instance.pending!=null || instance.departure!=null);}
    public static void noteSuppressedLoadingScreen(){suppressedLoadingScreens++;}
    public static int suppressedLoadingScreens(){return suppressedLoadingScreens;}
    public static boolean isActive() { return instance != null && (instance.anchor != null || instance.pending != null); }
    public static void turn(double horizontal, double vertical) {
        if (instance != null && instance.anchor != null && instance.pending == null)
            instance.flight.mouseLook(horizontal * 0.15, vertical * 0.15);
    }
    public static double telescopeFovScale() { return instance == null || instance.groundPlanet()==null && !isActive() ? 1 : 10 / instance.telescopeZoom; }
    public static boolean scrollTelescope(double delta) {
        var client = Minecraft.getInstance();
        if (instance == null || delta == 0 || client.player == null || !client.player.isScoping()
                || client.gui.screen() != null || instance.groundPlanet() == null && !isActive()) return false;
        instance.telescopeZoom = Math.clamp(instance.telescopeZoom * Math.pow(3, Math.signum(delta)), 10, 1000);
        message("Телескоп: ×" + (int) instance.telescopeZoom + " · колесо мыши — увеличение");
        return true;
    }
    /** Keep orbit visible over vanilla's dimension waiting screen until chunks arrive. */
    public static void drawPendingTravel() {
        var client = Minecraft.getInstance();
        if(client.level!=null && client.gui.screen()==null)return;
        if(instance!=null && instance.departure!=null){instance.draw(instance.snapshot(1));return;}
        if (instance != null && instance.pending != null && instance.pending.readyNanos == 0
                && (client.gui.screen() == null || client.gui.screen() instanceof LevelLoadingScreen))
            instance.draw(instance.travelFrame());
    }
    public static void drawSurfaceSky() {
        if(instance==null)return;
        if(instance.anchor==null || instance.pending!=null && instance.pending.readyNanos!=0)instance.draw(instance.surfaceSky());
    }

    /** Guaranteed even when the frame graph elides an entirely empty terrain pass. */
    public static void drawOrbitalScene() {
        if(instance==null)return;
        if(instance.pending!=null && instance.pending.readyNanos==0){instance.draw(instance.travelFrame());return;}
        if(instance.anchor!=null && instance.pending==null)instance.draw(instance.frame);
    }

    private Planet groundPlanet() {
        Minecraft client = Minecraft.getInstance();
        var server = client.getSingleplayerServer();
        if (client.level == null || server == null) return null;
        return client.level.dimension().equals(Level.OVERWORLD) ? universe.home()
                : PlanetWorlds.planetForLevel(server, client.level.dimension());
    }
    public static Planet currentSurfacePlanet() {
        return instance==null ? null : instance.groundPlanet();
    }

    private boolean canEnter(Minecraft client) {
        Planet planet=groundPlanet();
        return client.player != null && client.hasSingleplayerServer() && planet != null
                && (!PlanetSurface.cube(planet) || dev.seamlessspace.world.EarthProjection.contains(client.player.getX(),client.player.getZ()))
                && (client.player.isCreative() || client.player.isSpectator());
    }
    private double surfaceBase(Planet planet, double x, double z) {
        return planet.isHome() ? 64 : PlanetSurface.standingHeight(planet, (int) Math.floor(x), (int) Math.floor(z));
    }
    private double entryAltitude() {return universe.compact() ? Math.min(config.entryAltitude,2000) : config.entryAltitude;}

    private void launch() {
        Minecraft client = Minecraft.getInstance();
        if (anchor != null || pending != null) return;
        var source=groundPlanet();
        if(client.player!=null && source!=null && PlanetSurface.cube(source) && !dev.seamlessspace.world.EarthProjection.contains(client.player.getX(),client.player.getZ())) {
            message("Этот участок за орбитальной картой Земли: X −50000…50000, Z −16666…50000. Здесь доступен обычный креатив без вылета.");return;
        }
        if (!canEnter(client)) { message("Нужен одиночный мир и творческий режим или наблюдатель."); return; }
        enterSpace(groundPlanet(), true);
    }
    private void enterSpace(Planet source, boolean shortcut) {
        universe.selectPlanet(source);
        var client = Minecraft.getInstance();
        var player = client.player;
        DVec3 feet = vector(player.position());
        var sourceServer = client.getSingleplayerServer();
        var sourceDimension = client.level.dimension();
        sourceServer.execute(() -> {
            var serverPlayer=sourceServer.getPlayerList().getPlayer(player.getUUID());
            if(serverPlayer!=null) {
                serverPlayer.getAbilities().flying=true;
                serverPlayer.onUpdateAbilities();
            }
            var sourceLevel = sourceServer.getLevel(sourceDimension);
            if (sourceLevel != null) OrbitalSurfaceCache.captureRegion(sourceServer, sourceLevel,
                    ((int)Math.floor(feet.x())) >> 4, ((int)Math.floor(feet.z())) >> 4, 4);
        });
        ReturnPoint origin = new ReturnPoint(client.level.dimension(), feet);
        if (source.isHome()) homeReturn = origin;
        double base = surfaceBase(source, feet.x(), feet.z());
        double altitude = shortcut ? entryAltitude()+Math.max(0,feet.y()-base) : feet.y() - base;
        DVec3 physical = new DVec3(feet.x(), base + altitude, feet.z());
        anchor = new Anchor(player.getUUID(), sourceDimension, source, physical, player.isNoGravity(), origin);
        serverAnchor = anchor;
        if (renderer.hasFailed()) renderer.close();
        var local = PlanetSurface.frame(source, feet.x(), feet.z());
        DVec3 position = PlanetSurface.orbitalEye(source,physical,SpaceFlight.EYE_HEIGHT);
        DVec3 look = vector(player.getLookAngle());
        flight.engage(position, local.east().multiply(look.x()).add(local.radial().multiply(look.y())).add(local.north().multiply(look.z())));
        player.setNoGravity(false);
        // Vanilla applies a wider FOV to creative flight. Keep that state on
        // both sides of landing so the same terrain does not suddenly rescale.
        player.getAbilities().flying=true;
        renderFailed = false; groundFrame = null;
        // Keep the same vanilla player, dimension, input and velocity across the atmosphere.
        nativeEye=vector(player.position());departure=null;
        frame=snapshot(1);
    }

    private void clientTick(Minecraft client) {
        if(client.level!=null && client.player!=null && client.getSingleplayerServer()!=catalogServer) {
            catalogServer=client.getSingleplayerServer();
            universe.restoreSavedPlanets(catalogServer==null ? List.of() : PlanetWorlds.savedPlanets(catalogServer));
        }
        if (pending != null) { pollTravel(); return; }
        if (client.player == null || client.level == null) {
            anchor = null; frame = null; groundFrame = null;
            if (client.getSingleplayerServer() == null) homeReturn = null;
            return;
        }
        while(fasterKey.consumeClick())setSpeed(speedMultiplier*2);
        while(slowerKey.consumeClick())setSpeed(speedMultiplier/2);
        updateCreativeSpeed();
        while (launchKey.consumeClick()) launch();
        while (returnKey.consumeClick()) returnHome();
        while (landKey.consumeClick()) requestLanding(true);
        while (targetKey.consumeClick()) {
            if (anchor != null) { targetIndex = (targetIndex + 1) % universe.targets().size(); aimTarget(); }
        }
        if (pending != null) return;
        if (anchor == null) {
            if(client.level.dimension().equals(PlanetWorlds.SPACE)){returnHome();return;}
            Planet source = groundPlanet();
            if (source == null) return;
            universe.selectPlanet(source);
            PlanetEffects.tick(client,source);
            if(PlanetSurface.profile(source)<7 && source.body()==Planet.Body.SUN && client.level.getGameTime()%3==0) {
                double x=client.player.getX()+(Math.random()-.5)*30,z=client.player.getZ()+(Math.random()-.5)*30;
                int y=PlanetSurface.standingHeight(source,(int)x,(int)z);
                client.level.addParticle(net.minecraft.core.particles.ParticleTypes.LAVA,x,y+1,z,0,.25,0);
                client.level.addParticle(net.minecraft.core.particles.ParticleTypes.FLAME,x,y+1,z,0,.3,0);
            }
            double altitude = client.player.getY() - surfaceBase(source, client.player.getX(), client.player.getZ());
            if (altitude < entryAltitude() - 200) automaticEntryArmed = true;
            if (config.automaticEntry && automaticEntryArmed && canEnter(client)
                    && !client.player.isPassenger() && altitude >= entryAltitude()) enterSpace(source, false);
            return;
        }
        if(departure!=null) {
            if(departure.isCompletedExceptionally()){LOGGER.error("Space departure failed");departure=null;returnHome();return;}
            if(!departure.isDone() || !client.level.dimension().equals(PlanetWorlds.SPACE))return;
            departure=null;nativeEye=null;appliedCreativeSpeed=-1;
            if(returnAfterDeparture){returnAfterDeparture=false;returnHome();return;}
        }
        if(!client.level.dimension().equals(anchor.dimension())){clearFlight();return;}
        client.player.getAbilities().flying=true;
        advanceCreativeTick(client);
        frame=snapshot(1);
        prepareApproach();
        if(config.automaticLanding && flight.altitude()<=Math.max(1200,config.landingAltitude))requestLanding(false);
    }
    /** Read the same processed input used by vanilla; chunk stalls cannot pin the orbit. */
    private void advanceCreativeTick(Minecraft client) {
        if(client.isPaused() || client.gui.screen()!=null)return;
        var input=client.player.input;
        var move=input.getMoveVector();var keys=input.keyPresses;
        double forward=move.y,right=-move.x,vertical=(keys.jump()?1:0)-(keys.shift()?1:0);
        if(brakeKey.isDown()){forward=right=vertical=0;client.player.setDeltaMovement(Vec3.ZERO);}
        flight.advanceCreative(forward,right,vertical,keys.sprint(),speedMultiplier,.05);
    }
    private void setSpeed(double value){speedMultiplier=Math.clamp(value,.125,100000000);message("Скорость креатива: ×"+String.format(Locale.ROOT,"%.3g",speedMultiplier)+" · + / −");}
    private void updateCreativeSpeed() {
        var client=Minecraft.getInstance();if(client.player==null || !client.player.isCreative())return;
        double altitude=anchor!=null?flight.altitude():0;
        if(anchor==null){var planet=groundPlanet();if(planet!=null)altitude=client.player.getY()-surfaceBase(planet,client.player.getX(),client.player.getZ());}
        double cruise=10.9+Math.max(0,altitude-128)*.35*(1+Math.pow(Math.max(0,altitude)/100000,.65));
        float wanted=(float)(.05*Math.min(anchor==null?64:4,anchor==null?speedMultiplier*cruise/10.9:speedMultiplier));
        client.player.getAbilities().setFlyingSpeed(wanted);
        if(wanted==appliedCreativeSpeed)return;appliedCreativeSpeed=wanted;
        var server=client.getSingleplayerServer();if(server==null)return;var id=client.player.getUUID();
        server.execute(()->{var actor=server.getPlayerList().getPlayer(id);if(actor!=null){actor.getAbilities().setFlyingSpeed(wanted);actor.onUpdateAbilities();}});
    }

    private void extractFrame() {
        var client = Minecraft.getInstance();
        if (pending != null) return;
        if (anchor == null || client.player == null) {
            frame = null; groundFrame = surfaceSky(); return;
        }
        frame = snapshot(1);

    }
    private Frame snapshot(double alpha) {
        var client = Minecraft.getInstance();
        return new Frame(flight.position(), flight.forward(), flight.right(), flight.up(), universe.renderBodies(flight.position(), SpaceRenderer.MAX_PLANETS), alpha,
                tanHalfFov(), aspect(), flight.nearestPlanet(), flight.altitude(), flight.speed());
    }
    private Frame surfaceSky() {
        var client = Minecraft.getInstance();
        Planet planet = groundPlanet();
        if (planet == null || client.player == null) return null;
        var player=client.player;
        var camera=client.gameRenderer.mainCamera();
        Vec3 eye=camera.position();
        double altitude=eye.y-player.getEyeHeight()-surfaceBase(planet,eye.x,eye.z);
        var local=PlanetSurface.frame(planet,eye.x,eye.z);
        var view=camera.forwardVector();var viewRight=camera.leftVector();var viewUp=camera.upVector();
        DVec3 forward=local.east().multiply(view.x()).add(local.radial().multiply(view.y())).add(local.north().multiply(view.z()));
        DVec3 right=local.east().multiply(-viewRight.x()).add(local.radial().multiply(-viewRight.y())).add(local.north().multiply(-viewRight.z()));
        DVec3 up=local.east().multiply(viewUp.x()).add(local.radial().multiply(viewUp.y())).add(local.north().multiply(viewUp.z()));
        DVec3 position=PlanetSurface.orbitalEye(planet,new DVec3(eye.x,eye.y-player.getEyeHeight(),eye.z),player.getEyeHeight());
        List<Planet> others = universe.renderBodies(position, SpaceRenderer.MAX_PLANETS);
        return new Frame(position, forward, right, up, others, 1, tanHalfFov(), aspect(), planet, altitude, 0);
    }

    private static double tanHalfFov() {
        var client = Minecraft.getInstance();
        // Camera FOV includes the spyglass, sprint and other vanilla zoom modifiers.
        double fov = client.gameRenderer.mainCamera().getFov();
        if (!Double.isFinite(fov) || fov <= 0) fov = client.options.fov().get();
        return Math.tan(Math.toRadians(Math.clamp(fov, 0.05, 170)) / 2);
    }

    private Frame travelFrame() {
        PendingTravel travel = pending;
        if (travel == null || travel.retained == null) return null;
        Frame value = travel.retained;
        double alpha = 1;
        return new Frame(value.position(), value.forward(), value.right(), value.up(), value.planets(), alpha,
                value.tanHalfFov(), value.aspect(), value.nearest(), value.altitude(), value.speed());
    }

    private void prepareApproach() {
        long now = System.nanoTime();
        if (now < nextApproachCheck) return;
        nextApproachCheck = now + 500_000_000L;
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return;
        Planet planet = flight.nearestPlanet();
        if (flight.altitude() > 50_000) { releaseApproach(); return; }
        DVec3 radial = flight.position().subtract(planet.center()).normalized();
        var c = PlanetSurface.coordinates(planet, radial);
        if (approach != null && approach.planet.equals(planet)
                && Math.hypot(c.x() - approach.x, c.z() - approach.z) < 80) {
            Approach current = approach;
            if (current.result.isDone() && !current.result.isCompletedExceptionally())
                server.execute(() -> current.result.join().refresh());
            return;
        }
        // Bound generation pressure when a fast flight crosses many landing candidates.
        if(approachJobs.get()>=2){releaseApproach();return;}
        // Release an obsolete request immediately; its completion only removes its ticket.
        releaseApproach();
        var future = new CompletableFuture<PlanetWorlds.PreparedApproach>();
        approach = new Approach(planet, c.x(), c.z(), future);
        approachJobs.incrementAndGet();
        future.whenComplete((value,failure)->approachJobs.decrementAndGet());
        server.execute(() -> {
            try { PlanetWorlds.prepare(server, planet, radial).whenComplete((prepared, failure) -> {
                if (failure == null) future.complete(prepared); else future.completeExceptionally(failure);
            }); } catch (RuntimeException failure) { future.completeExceptionally(failure); }
        });
    }
    private void releaseApproach() {
        Approach old = approach; approach = null;
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (old != null && server != null) old.result.thenAccept(prepared -> server.execute(prepared::release));
    }
    private record Approach(Planet planet, double x, double z, CompletableFuture<PlanetWorlds.PreparedApproach> result) { }

    private void requestLanding(boolean manual) {
        var client = Minecraft.getInstance();
        if (anchor == null || departure!=null || pending != null || client.getSingleplayerServer() == null) return;
        if (flight.altitude() > Math.max(4096, config.landingAltitude * 4)) {
            if (manual) message("Подлети ближе к поверхности: посадка доступна ниже 4 км.");
            return;
        }
        Planet destination = flight.nearestPlanet();
        var future = new CompletableFuture<PlanetWorlds.LandingResult>();
        var travel = new PendingTravel(snapshot(1), anchor, future, false);
        pending = travel; serverAnchor = null; groundFrame = null;
        var server = client.getSingleplayerServer();
        DVec3 radial = flight.position().subtract(destination.center()).normalized();
        var projected=PlanetSurface.coordinates(destination,radial);
        double orbitalFeet=flight.position().distance(destination.center())-destination.radius()
                +(destination.isHome()?64:96)-SpaceFlight.EYE_HEIGHT;
        double altitude = Math.max(0,orbitalFeet-surfaceBase(destination,projected.x(),projected.z()));
        var local = PlanetSurface.frame(destination, PlanetSurface.coordinates(destination, radial).x(), PlanetSurface.coordinates(destination, radial).z());
        float yaw = (float)Math.toDegrees(Math.atan2(-flight.forward().dot(local.east()), flight.forward().dot(local.north())));
        float pitch = (float)-Math.toDegrees(Math.asin(Math.clamp(flight.forward().dot(local.radial()), -1, 1)));
        travel.yaw=yaw;travel.pitch=pitch;
        server.execute(() -> PlanetWorlds.arrive(server, travel.source.player(), destination, radial, altitude, yaw, pitch)
                .whenComplete((result, failure) -> { if (failure == null) future.complete(result); else future.completeExceptionally(failure); }));
    }
    private void pollTravel() {
        PendingTravel travel = pending;
        var client = Minecraft.getInstance();
        if (client.getSingleplayerServer() == null) { clearFlight(); pending = null; return; }
        if (!travel.result.isDone()) return;
        final PlanetWorlds.LandingResult result;
        try { result = travel.result.join(); }
        catch (RuntimeException failure) {
            LOGGER.error("Planet travel failed", failure);
            pending = null;
            if (client.level != null && client.level.dimension().equals(travel.source.dimension())) {
                anchor = travel.source; serverAnchor = anchor;
            } else clearFlight();
            message("Не удалось подготовить посадку. Подробности в logs/latest.log.");
            return;
        }
        if (client.player == null || client.level == null || !client.level.dimension().equals(result.dimensionKey())) return;
        client.player.setDeltaMovement(Vec3.ZERO);
        client.player.setPos(result.worldPosition().x(),result.worldPosition().y(),result.worldPosition().z());
        int x = (int) Math.floor(result.worldPosition().x()), z = (int) Math.floor(result.worldPosition().z());
        if (!client.level.getChunkSource().hasChunk(x >> 4, z >> 4) || client.gui.screen() instanceof LevelLoadingScreen) return;
        if(travel.chunksNanos==0)travel.chunksNanos=System.nanoTime();
        int ground=client.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,x,z)-1;
        if(!client.levelRenderer.isSectionCompiledAndVisible(new net.minecraft.core.BlockPos(x,ground,z),0)
                && System.nanoTime()-travel.chunksNanos<2_000_000_000L)return;
        // Let vanilla compile and fade in its normal chunk meshes underneath the orbital LOD.
        if (travel.readyNanos == 0) {
            travel.readyNanos = System.nanoTime();
            universe.restoreSavedPlanets(PlanetWorlds.savedPlanets(client.getSingleplayerServer()));
            if(!travel.returningHome) {
                client.player.setYRot(travel.yaw);client.player.setXRot(travel.pitch);
                client.player.setOldPosAndRot();
            }
            client.player.getAbilities().flying = !travel.returningHome && travel.retained != null
                    && travel.retained.altitude() > 2;
        }
        // Keep the same voxel backdrop on both sides of the handoff. Vanilla
        // replaces nearby columns in front of it; there is no full-screen dissolve.
        if (System.nanoTime() - travel.readyNanos < 50_000_000L) return;
        releaseApproach();
        clearFlight(); pending = null; automaticEntryArmed = false;
        client.player.setNoGravity(false); client.player.setDeltaMovement(Vec3.ZERO);
        message(travel.returningHome ? "Ты дома." : result.planet().name() + ": обычный творческий режим. Можно строить, ломать блоки и летать. F7 — в космос.");
    }

    private void returnHome() {
        if(departure!=null){returnAfterDeparture=true;return;}
        var client = Minecraft.getInstance();
        if (pending != null || client.player == null || client.getSingleplayerServer() == null) return;
        if (anchor == null && !client.level.dimension().equals(PlanetWorlds.SPACE) && (groundPlanet() == null || groundPlanet().isHome())) return;
        var server = client.getSingleplayerServer();
        ReturnPoint destination = homeReturn;
        var future = new CompletableFuture<PlanetWorlds.LandingResult>();
        Anchor source = anchor != null ? anchor : new Anchor(client.player.getUUID(), client.level.dimension(), groundPlanet(),
                vector(client.player.position()), false, new ReturnPoint(client.level.dimension(), vector(client.player.position())));
        pending = new PendingTravel(frame != null ? frame : surfaceSky(), source, future, true);
        serverAnchor = null;
        server.execute(() -> {
            try {
                var level = server.overworld();
                DVec3 pos = destination == null ? vector(Vec3.atCenterOf(level.getRespawnData().pos())).add(0, 2, 0) : destination.position();
                var center = new ChunkPos(((int) Math.floor(pos.x())) >> 4, ((int) Math.floor(pos.z())) >> 4);
                level.getChunkSource().addTicketAndLoadWithRadius(TicketType.PORTAL, center, 2).whenCompleteAsync((loaded, failure) -> {
                    try {
                        if (failure != null) { future.completeExceptionally(failure); return; }
                        var player = server.getPlayerList().getPlayer(source.player());
                        if (player == null) throw new IllegalStateException("Player disconnected");
                        player.stopRiding(); player.setGameMode(GameType.CREATIVE); player.setNoGravity(false);
                        if (!player.teleportTo(level, pos.x(), pos.y(), pos.z(), Set.of(), player.getYRot(), player.getXRot(), true))
                            throw new IllegalStateException("Home teleport rejected");
                        player.getAbilities().flying = false; player.onUpdateAbilities();
                        player.setDeltaMovement(Vec3.ZERO); player.resetFallDistance();
                        future.complete(new PlanetWorlds.LandingResult(Level.OVERWORLD, pos, universe.home()));
                    } catch (RuntimeException error) { future.completeExceptionally(error); }
                    finally { level.getChunkSource().removeTicketWithRadius(TicketType.PORTAL, center, 2); }
                }, server);
            } catch (RuntimeException failure) { future.completeExceptionally(failure); }
        });
    }
    private void clearFlight() { returnAfterDeparture=false;departure=null;nativeEye=null;appliedCreativeSpeed=-1;releaseApproach(); anchor = null; serverAnchor = null; frame = null; groundFrame = null; }

    private void draw(Frame current) {
        if (current == null || renderFailed) return;
        try {
            renderer.render(current.position(), current.forward(), current.right(), current.up(), current.planets(),
                    current.transition(), current.tanHalfFov(), current.aspect(), config.terrainDetail);
            if (renderer.hasFailed()) throw new IllegalStateException("Space shader failed");
        } catch (RuntimeException exception) {
            renderFailed = true; LOGGER.error("Space renderer failed", exception);
            if (anchor != null && pending == null) Minecraft.getInstance().execute(this::returnHome);
        }
    }
    private void aimTarget() {
        if (anchor == null) { message("Сначала F7 или /space launch."); return; }
        Planet target = universe.targets().get(targetIndex);
        universe.selectPlanet(target);
        flight.engage(flight.position(), target.center().subtract(flight.position()).normalized());
        message("Цель: " + target.name() + " · " + distance(target.distanceToSurface(flight.position())));
    }
    private void listLandmarks() {
        Planet planet=anchor==null?groundPlanet():flight.nearestPlanet();
        if(planet==null)return;
        if(PlanetSurface.profile(planet)<7){message("Особенности нового рельефа доступны на новых планетах в начале списка целей.");return;}
        message(planet.name()+": ориентиры поверхности (X Y Z)");
        for(var site:dev.seamlessspace.world.PlanetGeology.landmarks(planet))
            message(site.name()+": "+(int)site.x()+" "+(PlanetSurface.standingHeight(planet,(int)site.x(),(int)site.z())+8)+" "+(int)site.z());
    }

    private void listPlanets() {
        var client=Minecraft.getInstance();
        Planet source=groundPlanet();
        DVec3 position=anchor!=null ? flight.position() : source!=null && client.player!=null
                ? PlanetSurface.orbitalEye(source,vector(client.player.position()),client.player.getEyeHeight()) : new DVec3(0,64,0);
        int index = 0;
        for (Planet planet : universe.targets()) message(++index + ". " + planet.name()
                +((config.solarSystem ? !universe.homeSystem().contains(planet) : universe.compact() && !planet.id().startsWith("compact/")) ? " [старый мир]" : "")
                +" — " + distance(planet.distanceToSurface(position)));
    }
    private void drawHud(GuiGraphicsExtractor g) {
        var client = Minecraft.getInstance();
        if (!config.showHud || client.gameRenderer.gameRenderState().guiRenderState.isHudHidden || pending != null) return;
        if (frame == null) {
            Planet planet = groundPlanet();
            if (planet != null && !planet.isHome()) g.text(client.font, planet.name() + " · Творческий режим · F7: космос · F6: домой", 12, 12, 0xFFD1EBF0);
            return;
        }
        Frame current = frame;
        g.fill(10, 10, 320, 77, 0xA5101723);
        g.text(client.font, PlanetAtmosphere.spaceFraction(current.nearest(),current.altitude())<0.99 ? "SEAMLESS SPACE / АТМОСФЕРА" : "SEAMLESS SPACE / КОСМОС", 18, 18, 0xFF8EDCE6);
        g.text(client.font, current.nearest().name() + " · высота " + distance(current.altitude()), 18, 32, 0xFFF0F2F6);
        g.text(client.font, "Скорость " + distance(current.speed()) + "/с", 18, 45, 0xFFF0F2F6);
        g.text(client.font, "Креатив · +/−: скорость · B: тормоз · F6: домой", 18, 61, 0xFF9EAEC2);
        for (Planet planet : current.planets()) {
            DVec3 direction = planet.center().subtract(current.position()).normalized();
            double forward = direction.dot(current.forward());
            if (forward <= 0.01) continue;
            double nx = direction.dot(current.right()) / (forward * current.tanHalfFov() * current.aspect());
            double ny = direction.dot(current.up()) / (forward * current.tanHalfFov());
            if (Math.abs(nx) > 0.9 || Math.abs(ny) > 0.85) continue;
            int x = (int) ((nx + 1) * g.guiWidth() / 2), y = (int) ((1 - ny) * g.guiHeight() / 2);
            if ((x < 330 && y < 86) || y > g.guiHeight() - 64) continue;
            String label = planet.name() + " · " + distance(Math.max(0, planet.distanceToSurface(current.position())));
            g.fill(x - 2, y - 2, x + 3, y + 3, 0xA58EDCE6);
            int labelX = Math.max(8, Math.min(x + 7, g.guiWidth() - client.font.width(label) - 8));
            g.text(client.font, label, labelX, y - 3, 0xFFAFD3DA);
        }
        Planet target = universe.targets().get(Math.min(targetIndex,universe.targets().size()-1));
        g.text(client.font, "N → " + target.name() + " · " + distance(target.distanceToSurface(current.position())), 12, g.guiHeight() - 52, 0xFFBDECF4);
    }
    private static double aspect() {
        var window = Minecraft.getInstance().getWindow();
        return (double) window.getWidth() / Math.max(1, window.getHeight());
    }
    private static DVec3 vector(Vec3 v) { return new DVec3(v.x, v.y, v.z); }
    private static double axis(KeyMapping p, KeyMapping n) { return (p.isDown() ? 1 : 0) - (n.isDown() ? 1 : 0); }
    private static String distance(double blocks) {
        if (Math.abs(blocks) >= 1e9) return String.format(Locale.ROOT, "%.2f млн км", blocks / 1e9);
        if (Math.abs(blocks) >= 10000) return String.format(Locale.ROOT, "%.1f км", blocks / 1000);
        return String.format(Locale.ROOT, "%.0f м", blocks);
    }
    private static void message(String text) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.sendSystemMessage(Component.literal(text));
    }
    private record ReturnPoint(ResourceKey<Level> dimension, DVec3 position) { }
    private record Anchor(UUID player, ResourceKey<Level> dimension, Planet source, DVec3 position,
                          boolean noGravity, ReturnPoint returnPoint) { }
    private record Frame(DVec3 position, DVec3 forward, DVec3 right, DVec3 up, List<Planet> planets,
                         double transition, double tanHalfFov, double aspect, Planet nearest, double altitude, double speed) { }
    private static final class PendingTravel {
        final Frame retained;
        final Anchor source;
        final CompletableFuture<PlanetWorlds.LandingResult> result;
        final boolean returningHome;
        long readyNanos,chunksNanos;
        float yaw,pitch;
        PendingTravel(Frame retained, Anchor source, CompletableFuture<PlanetWorlds.LandingResult> result, boolean returningHome) {
            this.retained = retained; this.source = source; this.result = result; this.returningHome = returningHome;
        }
    }
}
