package dev.seamlessspace.universe;

/** A double precision position in the universe, measured in Minecraft blocks. */
public record DVec3(double x, double y, double z) {
    public static final DVec3 ZERO = new DVec3(0, 0, 0);
    public static final DVec3 UP = new DVec3(0, 1, 0);

    public DVec3 add(DVec3 other) {
        return new DVec3(x + other.x, y + other.y, z + other.z);
    }

    public DVec3 add(double dx, double dy, double dz) {
        return new DVec3(x + dx, y + dy, z + dz);
    }

    public DVec3 subtract(DVec3 other) {
        return new DVec3(x - other.x, y - other.y, z - other.z);
    }

    public DVec3 multiply(double factor) {
        return new DVec3(x * factor, y * factor, z * factor);
    }

    public DVec3 divide(double divisor) {
        return multiply(1.0 / divisor);
    }

    public DVec3 negate() {
        return new DVec3(-x, -y, -z);
    }

    public double dot(DVec3 other) {
        return x * other.x + y * other.y + z * other.z;
    }

    public DVec3 cross(DVec3 other) {
        return new DVec3(y * other.z - z * other.y,
                z * other.x - x * other.z, x * other.y - y * other.x);
    }

    public double lengthSquared() {
        return dot(this);
    }

    public double length() {
        return Math.sqrt(lengthSquared());
    }

    public DVec3 normalized() {
        double length = length();
        return length > 1.0e-15 ? divide(length) : ZERO;
    }

    public double distance(DVec3 other) {
        return subtract(other).length();
    }

    public double distanceSquared(DVec3 other) {
        return subtract(other).lengthSquared();
    }

    public DVec3 lerp(DVec3 other, double fraction) {
        return multiply(1 - fraction).add(other.multiply(fraction));
    }

    public boolean isFinite() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }
}
