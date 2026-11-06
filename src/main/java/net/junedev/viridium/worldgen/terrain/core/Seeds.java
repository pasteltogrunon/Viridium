package net.junedev.viridium.worldgen.terrain.core;

/**
 * Per-feature noise seeds derived from the world seed. The order of {@link #NAMES} matters: it matches
 * {@code SEED_NAMES} in worldgen.js, so do not reorder; append new names at the end.
 */
public final class Seeds {

    static final String[] NAMES = { "warp", "plate", "vel", "cont", "contN", "hills", "mmask", "mtn", "sea", "seaR",
        "temp", "prec", "wind", "rock", "rockP", "rockW", "ero", "rivJ", "rivW", "coll", "isl", "micro", "arcN", "hot",
        "ice", "berg", "delta", "wind2", "lake", "cur", "estu", "mea",
        // Java-only from here on (crust)
        "crust", "strata", "pluton", "dike", "pipe", "fold", "basin", "basinW",
        // landforms
        "plat", "rift", "cliff", "fall" };

    public final int warp, plate, vel, cont, contN, hills, mmask, mtn, sea, seaR, temp, prec, wind, rock, rockP, rockW,
        ero, rivJ, rivW, coll, isl, micro, arcN, hot, ice, berg, delta, wind2, lake, cur, estu, mea;
    public final int crust, strata, pluton, dike, pipe, fold, basin, basinW;
    public final int plat, rift, cliff, fall;

    public Seeds(int seed) {
        int[] s = new int[NAMES.length];
        for (int i = 0; i < s.length; i++) s[i] = Noise.hash(seed, i * 7919 + 1, 0x5eed) & 0x3fffffff;
        int k = 0;
        warp = s[k++];
        plate = s[k++];
        vel = s[k++];
        cont = s[k++];
        contN = s[k++];
        hills = s[k++];
        mmask = s[k++];
        mtn = s[k++];
        sea = s[k++];
        seaR = s[k++];
        temp = s[k++];
        prec = s[k++];
        wind = s[k++];
        rock = s[k++];
        rockP = s[k++];
        rockW = s[k++];
        ero = s[k++];
        rivJ = s[k++];
        rivW = s[k++];
        coll = s[k++];
        isl = s[k++];
        micro = s[k++];
        arcN = s[k++];
        hot = s[k++];
        ice = s[k++];
        berg = s[k++];
        delta = s[k++];
        wind2 = s[k++];
        lake = s[k++];
        cur = s[k++];
        estu = s[k++];
        mea = s[k++];
        crust = s[k++];
        strata = s[k++];
        pluton = s[k++];
        dike = s[k++];
        pipe = s[k++];
        fold = s[k++];
        basin = s[k++];
        basinW = s[k++];
        plat = s[k++];
        rift = s[k++];
        cliff = s[k++];
        fall = s[k++];
    }
}
