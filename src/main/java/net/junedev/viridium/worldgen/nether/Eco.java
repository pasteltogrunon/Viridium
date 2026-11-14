package net.junedev.viridium.worldgen.nether;

/**
 * Ecosystems (design §15): names describing the result of conditions already generated (activity, closeness to lava,
 * openness, substrate, growth). They decide surface covers and small life on floors; the ceiling gardens are a flag.
 */
public enum Eco {

    /** Bare rock of the substrate. */
    BARE,
    /** Scoria and obsidian crust next to active lava. */
    CRUST,
    /** Ash basins: loose cinder near activity, open spaces, scattered fire. */
    ASH,
    /** Soul plains: soul sand with nether wart. */
    SOUL,
    /** Mineral ossuaries: soul sand and bone-like spikes. */
    OSSUARY,
    /** Extinct domains colonised by fungi. */
    FUNGAL,
    /** Infernal reefs: quartz around energy sources. */
    REEF;

    public static final Eco[] VALUES = values();
}
