package net.junedev.viridium.worldgen.terrain.crust;

/** Geological provinces (see docs/viridium/Design/Geology.md). Decide the cover and the basement of a column. */
public enum Province {
    /** Stable interior with flat-lying sedimentary strata. */
    PLATFORM,
    /** Thick, fine-grained sediments in a low area. */
    BASIN,
    /** Exposed crystalline basement (craton). */
    SHIELD,
    /** Mountain belt: folded metasediments over gneiss, deep crustal root. */
    OROGEN,
    /** Volcanic arc over a subduction zone. */
    ARC,
    /** Large igneous province: stacked basalt flows. */
    LIP,
    /** Passive margin / continental shelf: marine sediments over thinned crust. */
    MARGIN,
    /** Oceanic crust (ophiolite sequence). */
    OCEANIC,
    /** Oceanic island volcano (hotspot), possibly with a reef cap. */
    VOLCANIC_ISLAND;

    public static final Province[] VALUES = values();
}
