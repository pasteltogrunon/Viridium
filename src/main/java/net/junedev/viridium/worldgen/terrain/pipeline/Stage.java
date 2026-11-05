package net.junedev.viridium.worldgen.terrain.pipeline;

/** Pipeline stages, in evaluation order. Evaluating up to a stage runs it and every stage before it. */
public enum Stage {
    /** 1-2. Base temperature, precipitation and general circulation. */
    CLIMATE,
    /** 3-4. Tectonic plates, ridges, trenches, arcs and collisions. */
    PLATES,
    /** 5/13. Continents, microcontinents and shelf islands. */
    CONTINENTS,
    /** 7. Base height plus volcanism. */
    RELIEF,
    /** 6. Surface rock patches and erodibility. */
    GEOLOGY;
}
