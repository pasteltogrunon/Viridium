package net.junedev.viridium.worldgen.nether;

/**
 * Formal families of the surreal grammar (design §9). They are combinable transformations, not closed biomes: a core
 * has a dominant and a secondary one, and every place carries a weight for each.
 */
public enum Transform {

    /** Curved strata and inclined ledges; at the centre, surfaces that bend and wrap large voids. */
    FOLD,
    /** Rhythms of pillars and arches; at the centre, monumental series with small anomalies. */
    REPETITION,
    /** Fragments detached from walls and ceilings; at the centre, clusters of suspended masses. */
    SUSPENSION,
    /** Branching veins and protrusions; at the centre, large mineral architecture. */
    GROWTH,
    /** Hollows inside recognisable bodies; at the centre, nested envelopes. */
    HOLLOWING;

    public static final Transform[] VALUES = values();
    public static final int COUNT = VALUES.length;
}
