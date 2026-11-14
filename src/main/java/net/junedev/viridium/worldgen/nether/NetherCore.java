package net.junedev.viridium.worldgen.nether;

/**
 * A core (design §7): a focus of one form of transformation of the world. Its position and properties derive from the
 * substrate; its influence creates the surrounding domain, whose visible cell is its footprint. Its landmark is the
 * maximum expression of the same rules (drawn by {@link Landmarks}).
 */
public final class NetherCore {

    /** Activity thresholds on {@link #activity}. */
    public static final double ACTIVE = 0.3, EXTINCT = -0.15;

    public final int cellX, cellZ, tier;
    /** Unique id (also a seed for its details). */
    public final int id;
    /** Centre of the landmark (absolute). */
    public final double x, y, z;
    /** Floor of the core cavity (absolute Y) and radius of the cavity. */
    public final double floorY, cavityR;
    /** Reach (blocks): distance up to which its effects are recognisable. */
    public final double reach;
    /** Magnitude of the transformation near the origin, in (0, 1]. */
    public final double intensity;
    /** Preferred direction of propagation (radians), its cos/sin, and the stretch along it (>= 1). */
    public final double orient, cos, sin, aniso;
    public final Transform dominant, secondary;
    /** Activity in [-1, 1]: active above {@link #ACTIVE}, extinct below {@link #EXTINCT}. */
    public final double activity;
    public final double thermal;
    public final SubstrateKind substrate;
    /** Fracture the core sits on, and its distance to it (influence travels along it). */
    public final long fractureId;
    public final double fractureDist;
    /** Preferred openness of its domain in [0, 1] (rock/void proportion). */
    public final double openPref;

    NetherCore(int cellX, int cellZ, int tier, int id, double x, double y, double z, double floorY, double reach,
        double intensity, double orient, double aniso, Transform dominant, Transform secondary, double activity,
        double thermal, SubstrateKind substrate, long fractureId, double fractureDist, double openPref) {
        this.cellX = cellX;
        this.cellZ = cellZ;
        this.tier = tier;
        this.id = id;
        this.x = x;
        this.y = y;
        this.z = z;
        this.floorY = floorY;
        this.reach = reach;
        this.cavityR = Math.max(52, Math.min(92, 0.23 * reach));
        this.intensity = intensity;
        this.orient = orient;
        this.cos = Math.cos(orient);
        this.sin = Math.sin(orient);
        this.aniso = aniso;
        this.dominant = dominant;
        this.secondary = secondary;
        this.activity = activity;
        this.thermal = thermal;
        this.substrate = substrate;
        this.fractureId = fractureId;
        this.fractureDist = fractureDist;
        this.openPref = openPref;
    }

    public boolean active() {
        return activity > ACTIVE;
    }

    public boolean extinct() {
        return activity < EXTINCT;
    }

    /** Horizontal distance to the core axis. */
    public double distH(double px, double pz) {
        double dx = px - x, dz = pz - z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    @Override
    public String toString() {
        return String.format(
            "core[t%d %s/%s %.0f,%.0f,%.0f R%.0f I%.2f act%.2f %s]",
            tier,
            dominant,
            secondary,
            x,
            y,
            z,
            reach,
            intensity,
            activity,
            substrate);
    }
}
