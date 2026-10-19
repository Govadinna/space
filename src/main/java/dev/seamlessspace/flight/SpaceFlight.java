package dev.seamlessspace.flight;

import dev.seamlessspace.universe.DVec3;
import dev.seamlessspace.universe.Planet;
import dev.seamlessspace.universe.Universe;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Client-side, double precision movement through the continuously rendered universe. */
public final class SpaceFlight {
    public static final double EYE_HEIGHT = 1.62;
    private static final double SKIN = 0.035;
    private static final double CONTACT_EPSILON = 0.002;
    private static final double GRAVITY = 24;
    private final Universe universe;
    private final double maxCruiseSpeed;
    private DVec3 position = new DVec3(0, 10_064, 0);
    private DVec3 velocity = DVec3.ZERO;
    private DVec3 radialUp = DVec3.UP;
    private DVec3 heading = new DVec3(0, 0, -1);
    private double pitch;
    private Planet nearest;
    private boolean landed;
    private boolean jumpHeld;

    public record Input(double forward, double strafe, double vertical,
                        boolean boost, boolean brake, boolean walking) {
        public static final Input NONE = new Input(0, 0, 0, false, false, false);

        public Input {
            forward = axis(forward);
            strafe = axis(strafe);
            vertical = axis(vertical);
        }

        private static double axis(double value) {
            return Double.isFinite(value) ? Math.clamp(value, -1, 1) : 0;
        }
    }

    public SpaceFlight(Universe universe, double maxCruiseSpeed) {
        this.universe = Objects.requireNonNull(universe, "universe");
        if (!Double.isFinite(maxCruiseSpeed) || maxCruiseSpeed < 8 || maxCruiseSpeed > 5_000_000_000.0) {
            throw new IllegalArgumentException("Cruise speed must be between 8 and 5000000000 blocks/s");
        }
        this.maxCruiseSpeed = maxCruiseSpeed;
        nearest = universe.home();
    }

    public SpaceFlight(Universe universe) {
        this(universe, 200_000);
    }

    /** Enter at a world position; the eye position is kept outside the terrain. */
    public void engage(DVec3 newPosition, DVec3 newForward) {
        if (newPosition == null || newForward == null || !newPosition.isFinite()
                || !newForward.isFinite() || newForward.lengthSquared() < 1.0e-20) {
            throw new IllegalArgumentException("Position and a nonzero viewing direction must be finite");
        }
        position = newPosition;
        velocity = DVec3.ZERO;
        nearest = universe.nearby(position, 1).getFirst();
        radialUp = preferredUp(nearest,position);
        if (radialUp.lengthSquared() < 0.5) radialUp = DVec3.UP;
        DVec3 look = newForward.normalized();
        pitch = Math.asin(Math.clamp(look.dot(radialUp), -1, 1));
        pitch = Math.clamp(pitch, -Math.PI * 0.5, Math.PI * 0.5);
        heading = tangent(look, radialUp);
        double floor = universe.surfaceRadius(nearest, radialUp) + EYE_HEIGHT + SKIN;
        if (position.distance(nearest.center()) < floor) {
            position = nearest.center().add(radialUp.multiply(floor));
        }
        landed = altitude() <= SKIN + 0.01;
        jumpHeld = false;
    }

    /** Positive yaw turns right, positive pitch looks down (Minecraft mouse convention). */
    public void mouseLook(double deltaYaw, double deltaPitch) {
        if (!Double.isFinite(deltaYaw) || !Double.isFinite(deltaPitch)) return;
        heading = rotate(heading, radialUp, -Math.toRadians(deltaYaw)).normalized();
        pitch = Math.clamp(pitch - Math.toRadians(deltaPitch), -Math.PI * 0.5, Math.PI * 0.5);
    }

    /** A stall cannot teleport the camera: at most 0.25 seconds is advanced per call. */
    public void tick(double dt, Input input) {
        Objects.requireNonNull(input, "input");
        if (!Double.isFinite(dt) || dt <= 0) return;
        dt = Math.min(dt, 0.25);
        int steps = Math.max(1, (int) Math.ceil(dt * 240));
        double step = dt / steps;
        boolean jump = input.walking() && input.vertical() > 0.5 && !jumpHeld;
        for (int i = 0; i < steps; i++) {
            updateFrame(step);
            if (input.walking()) walk(step, input, jump && i == 0);
            else fly(step, input);
        }
        jumpHeld = input.walking() && input.vertical() > 0.5;
        updateFrame(0);
    }

    /** Native creative player displacement, transported into the double-precision orbital frame. */
    public void advanceNative(DVec3 displacement,double dt) {
        if(!displacement.isFinite() || !Double.isFinite(dt) || dt<=0)return;
        updateFrame(Math.min(dt,.1));
        double altitude=Math.max(0,altitude());
        double maximum=altitude<universe.approachBand()+2*universe.terrainAmplitudeBound(nearest)?Math.max(80,altitude*.35):Double.POSITIVE_INFINITY;
        if(displacement.length()>maximum*dt)displacement=displacement.normalized().multiply(maximum*dt);
        var before=position;move(displacement);velocity=position.subtract(before).multiply(1/dt);
        updateFrame(0);
    }
    /** Vanilla creative axes: W/S horizontal, A/D lateral, Space/Shift vertical.
     * Orbital movement must not wait for a native chunk to arrive. */
    public void advanceCreative(double forward,double right,double vertical,boolean boost,double multiplier,double dt) {
        DVec3 horizontal=forward().subtract(radialUp.multiply(forward().dot(radialUp)));
        if(horizontal.lengthSquared()<1e-8)horizontal=radialUp.cross(right());
        DVec3 intent=horizontal.normalized().multiply(forward).add(right().multiply(right)).add(radialUp.multiply(vertical));
        if(intent.lengthSquared()>1)intent=intent.normalized();
        advanceNative(intent.multiply(Math.min(maxCruiseSpeed*10,creativeCruise(boost)*multiplier)*dt),dt);
    }
    public double creativeCruise(boolean boost) {
        double a=Math.max(0,altitude());
        double speed=Math.min(maxCruiseSpeed,10.9+Math.max(0,a-128)*.35*(1+Math.pow(a/100000,.65)));
        return speed*(boost?1+9*smooth(Math.clamp((a-200)/20000,0,1)):1);
    }
    private void fly(double dt, Input input) {
        double altitude = Math.max(0, altitude());
        double cruise = Math.min(maxCruiseSpeed, 80 + altitude * 0.35
                * (1 + Math.pow(altitude / 100_000, 0.65)));
        double boost = input.boost() ? 1 + 9 * smooth(Math.clamp((altitude - 200) / 20_000, 0, 1)) : 1;
        DVec3 intent = forward().multiply(input.forward()).add(right().multiply(input.strafe()))
                .add(radialUp.multiply(input.vertical()));
        if (intent.lengthSquared() > 1) intent = intent.normalized();
        DVec3 target = input.brake() ? DVec3.ZERO : intent.multiply(cruise * boost);
        double damping = input.brake() ? 14 : intent.lengthSquared() > 0 ? 3.5 : 2;
        DVec3 before = velocity;
        double decay = Math.exp(-damping * dt);
        velocity = target.add(before.subtract(target).multiply(decay));
        DVec3 displacement = target.multiply(dt).add(before.subtract(target).multiply((1 - decay) / damping));
        // Slow the camera before the surface can stream in. Also limit residual
        // momentum: merely changing the desired speed still lets a fast ship overshoot.
        if (altitude < universe.approachBand()+2*universe.terrainAmplitudeBound(nearest)) {
            double safeSpeed = Math.max(80, altitude * 0.35);
            if (velocity.length() > safeSpeed) velocity = velocity.normalized().multiply(safeSpeed);
            if (displacement.length() > safeSpeed * dt) displacement = displacement.normalized().multiply(safeSpeed * dt);
        }
        if(nearest.hasRings()) {
            var o=position.subtract(nearest.center());var n=nearest.ringNormal();
            double vertical=o.dot(n),ringRadius=o.subtract(n.multiply(vertical)).length()/nearest.radius();
            if(ringRadius>1.3 && ringRadius<2.5 && Math.abs(vertical)<1000) {
                double safe=Math.max(18,Math.abs(vertical)*.5);
                if(velocity.length()>safe)velocity=velocity.normalized().multiply(safe);
                if(displacement.length()>safe*dt)displacement=displacement.normalized().multiply(safe*dt);
            }
        }
        move(displacement);
    }

    private void walk(double dt, Input input, boolean jump) {
        DVec3 unit = position.subtract(nearest.center()).normalized();
        double clearance = clearance(nearest, position);
        boolean supported = clearance < 0.12 && velocity.dot(unit) <= 0.5;
        DVec3 desired = heading.multiply(input.forward()).add(right().multiply(input.strafe()));
        desired = desired.subtract(unit.multiply(desired.dot(unit)));
        if (desired.lengthSquared() > 1) desired = desired.normalized();
        desired = desired.multiply(input.brake() ? 0 : input.boost() ? 7 : 4.3);
        double verticalSpeed = velocity.dot(unit);
        if (supported) verticalSpeed = jump ? 8 : 0;
        DVec3 horizontal = velocity.subtract(unit.multiply(velocity.dot(unit)));
        double decay = Math.exp(-12 * dt);
        DVec3 travel = desired.multiply(dt).add(horizontal.subtract(desired).multiply((1 - decay) / 12));
        horizontal = desired.add(horizontal.subtract(desired).multiply(decay));
        if (!supported || jump) {
            travel = travel.add(unit.multiply(verticalSpeed * dt - GRAVITY * dt * dt * 0.5));
            verticalSpeed -= GRAVITY * dt;
        }
        velocity = horizontal.add(unit.multiply(verticalSpeed));
        if (supported && !jump) {
            // Follow the actual radial height field; no flat-world coordinate reset at the poles.
            DVec3 next = position.add(travel);
            DVec3 nextUnit = next.subtract(nearest.center()).normalized();
            double nextFloor = universe.surfaceRadius(nearest, nextUnit) + EYE_HEIGHT + SKIN;
            if (Math.abs(next.distance(nearest.center()) - nextFloor) < 1.1) {
                position = nearest.center().add(nextUnit.multiply(nextFloor));
                landed = true;
                return;
            }
        }
        move(travel);
    }

    private void move(DVec3 displacement) {
        if (displacement.lengthSquared() < 1.0e-24) {
            landed = clearance(nearest, position) <= SKIN + 0.01;
            return;
        }
        Hit first = null;
        double length = displacement.length();
        DVec3 direction = displacement.multiply(1 / length);
        // Query every crossed sector, so a fast flight cannot miss an unvisited system.
        Map<String, Planet> candidates = new LinkedHashMap<>();
        int queries = Math.max(1, (int) Math.ceil(length / (universe.sectorSize() * 0.25)));
        for (int i = 0; i <= queries; i++) {
            DVec3 sample = position.add(displacement.multiply((double) i / queries));
            for (Planet planet : universe.nearby(sample, 64)) candidates.put(planet.id(), planet);
        }
        for (Planet planet : candidates.values()) {
            // A single interplanetary step may cross the whole approach band.
            // Stop at its outer boundary first, leaving time to prepare real chunks.
            double band = planet.radius() + universe.approachBand() + universe.terrainAmplitudeBound(planet) * 2;
            if (position.distance(planet.center()) > band) {
                double entry = Universe.raySphere(position, direction, planet.center(), band);
                if (entry < length) {
                    length = entry;
                    displacement = direction.multiply(length);
                    double entrySpeed=universe.approachBand()*.35;
                    if (velocity.length() > entrySpeed) velocity = velocity.normalized().multiply(entrySpeed);
                }
            }
            Hit hit = sweep(planet, position, direction, length);
            if (hit != null && (first == null || hit.distance < first.distance)) first = hit;
        }
        if (first == null) {
            position = position.add(displacement);
            landed = clearance(nearest, position) <= SKIN + 0.01;
            return;
        }
        position = position.add(direction.multiply(first.distance));
        DVec3 unit = position.subtract(first.planet.center()).normalized();
        DVec3 normal = universe.surfaceNormal(first.planet, unit);
        // Keep a small positive clearance to permit tangential movement on the next frame.
        double floor = universe.surfaceRadius(first.planet, unit) + EYE_HEIGHT + SKIN;
        if (position.distance(first.planet.center()) < floor) {
            position = first.planet.center().add(unit.multiply(floor));
        }
        double inward = velocity.dot(normal);
        if (inward < 0) velocity = velocity.subtract(normal.multiply(inward));
        landed = true;
        nearest = first.planet;
    }

    private Hit sweep(Planet planet, DVec3 start, DVec3 direction, double length) {
        double relief = universe.relief(planet);
        double outerRadius = planet.radius() + 2 * universe.terrainAmplitudeBound(planet) + EYE_HEIGHT + SKIN;
        DVec3 offset = start.subtract(planet.center());
        double projection = offset.dot(direction);
        double perpendicular2 = offset.subtract(direction.multiply(projection)).lengthSquared();
        double discriminant = outerRadius * outerRadius - perpendicular2;
        if (discriminant < 0) return null;
        double chord = Math.sqrt(discriminant);
        double t = Math.max(0, -projection - chord);
        double end = Math.min(length, -projection + chord);
        if (t > end) return null;
        // Conservative bound for the gradient of all seven smooth value-noise octaves.
        // Home has an additional constant height offset, already covered by the outer sphere.
        double minimumRadius = Math.max(planet.radius() * 0.1,
                planet.radius() - 2 * universe.terrainAmplitudeBound(planet) - EYE_HEIGHT);
        double lipschitz = (dev.seamlessspace.world.PlanetSurface.profile(planet) >= 3 ? 12 : 2)
                + 128 * relief / minimumRadius;
        for (int iteration = 0; iteration < 4096 && t <= end; iteration++) {
            DVec3 point = start.add(direction.multiply(t));
            double distance = clearance(planet, point);
            if (distance <= CONTACT_EPSILON) return new Hit(planet, t);
            double advance = Math.max(0.0001, (distance - CONTACT_EPSILON) / lipschitz * 0.85);
            if (advance > end - t) return null;
            t += advance;
        }
        // A pathological grazing ray may converge slowly. Stop conservatively rather than tunnel.
        return t <= end ? new Hit(planet, t) : null;
    }

    private void updateFrame(double dt) {
        nearest = universe.nearby(position, 1).getFirst();
        DVec3 targetUp = preferredUp(nearest,position);
        if (targetUp.lengthSquared() < 0.5) return;
        double influence = 1 - smooth(Math.clamp((altitude() - 5_000) / Math.max(50_000, nearest.radius()), 0, 1));
        if (influence <= 0 || dt <= 0) return;
        double angle = Math.acos(Math.clamp(radialUp.dot(targetUp), -1, 1));
        if (angle < 1.0e-10) return;
        DVec3 axis = radialUp.cross(targetUp);
        if (axis.lengthSquared() < 1.0e-20) axis = heading;
        axis = axis.normalized();
        double turn = Math.min(angle, dt * 2 * influence);
        heading = rotate(heading, axis, turn);
        radialUp = rotate(radialUp, axis, turn).normalized();
        heading = tangent(heading, radialUp);
    }

    private double clearance(Planet planet, DVec3 point) {
        DVec3 radial = point.subtract(planet.center());
        double radius = radial.length();
        if (radius < 1.0e-12) return -planet.radius();
        return radius - universe.surfaceRadius(planet, radial.multiply(1 / radius)) - EYE_HEIGHT;
    }

    private static DVec3 preferredUp(Planet planet,DVec3 position) {
        var offset=position.subtract(planet.center());
        if(planet.hasRings()) {
            var normal=planet.ringNormal();double h=offset.dot(normal);
            double radius=offset.subtract(normal.multiply(h)).length()/planet.radius();
            if(Math.abs(h)<1000 && radius>1.3 && radius<2.5)return normal;
        }
        return offset.normalized();
    }

    private static DVec3 tangent(DVec3 vector, DVec3 normal) {
        DVec3 result = vector.subtract(normal.multiply(vector.dot(normal)));
        if (result.lengthSquared() < 1.0e-20) {
            DVec3 helper = Math.abs(normal.y()) < 0.9 ? DVec3.UP : new DVec3(1, 0, 0);
            result = helper.cross(normal);
        }
        return result.normalized();
    }

    private static DVec3 rotate(DVec3 vector, DVec3 axis, double angle) {
        double c = Math.cos(angle), s = Math.sin(angle);
        return vector.multiply(c).add(axis.cross(vector).multiply(s)).add(axis.multiply(axis.dot(vector) * (1 - c)));
    }

    private static double smooth(double value) { return value * value * (3 - 2 * value); }

    public DVec3 position() { return position; }
    public DVec3 velocity() { return velocity; }
    public DVec3 forward() { return heading.multiply(Math.cos(pitch)).add(radialUp.multiply(Math.sin(pitch))).normalized(); }
    public DVec3 right() { return heading.cross(radialUp).normalized(); }
    public DVec3 up() { return right().cross(forward()).normalized(); }
    public DVec3 radialUp() { return radialUp; }
    public double altitude() { return clearance(nearest, position); }
    public double speed() { return velocity.length(); }
    public Planet nearestPlanet() { return nearest; }
    public boolean landed() { return landed; }

    /** Safe orbit above the original Minecraft spawn; does not change the vanilla player. */
    public void returnHome() { engage(new DVec3(0, 10_064, 0), new DVec3(0, 0, -1)); }

    private record Hit(Planet planet, double distance) { }
}
