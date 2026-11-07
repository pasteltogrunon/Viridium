package net.junedev.viridium.worldgen.terrain.caves;

import java.util.List;

/** Planned cave pieces (karst shafts, giant chambers, galleries) touching a cube. */
public interface CaveFeatures {

    /** Adds every shape whose bounding box may touch the 16³ cube at block (bx, by, bz). */
    void collect(int bx, int by, int bz, List<CaveCarver.Shape> out);
}
