package dev.seamlessspace.universe;

import java.util.Objects;

/** Planet positions and base radii are in world blocks; terrain is evaluated on demand. */
public record Planet(String id, String name, DVec3 center, double radius, Kind kind, long seed) {
    /** Ordinals are also the renderer's material identifiers. Keep this order stable. */
    public enum Kind {
        ROCKY, DESERT, ICE, LAVA, OCEAN
    }

    public enum Body { GENERIC, MERCURY, VENUS, EARTH, MOON, MARS, JUPITER, SATURN, URANUS, NEPTUNE, SUN }
    public Body body() {
        if(!id.startsWith("solar-v1/") && !id.startsWith("solar-v2/") && !id.startsWith("solar-v3/") && !id.startsWith("solar-v4/") && !id.startsWith("solar-v5/")) return Body.GENERIC;
        try {return Body.valueOf(id.substring(9).toUpperCase(java.util.Locale.ROOT));}
        catch(IllegalArgumentException ignored) {return Body.GENERIC;}
    }
    public boolean isSolar() {return body()!=Body.GENERIC;}
    public double northScale() {return body()==Body.EARTH ? 2 : 1;}
    public boolean hasRings() {return body()==Body.SATURN || body()==Body.URANUS;}
    public DVec3 ringNormal() {return (body()==Body.URANUS ? new DVec3(.94,.18,.29) : new DVec3(.18,.92,.34)).normalized();}

    public Planet {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(kind, "kind");
        if (id.isBlank() || name.isBlank()) {
            throw new IllegalArgumentException("A planet needs an id and name");
        }
        if (!center.isFinite() || !Double.isFinite(radius) || radius <= 0) {
            throw new IllegalArgumentException("A planet needs a finite center and positive radius");
        }
    }

    public double distanceToSurface(DVec3 position) {
        return position.distance(center) - radius;
    }

    public boolean isHome() {
        return id.equals("home") || id.equals("compact/home") || id.equals("solar-v1/earth");
    }
}
