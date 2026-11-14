package net.junedev.viridium.worldgen.nether;

/**
 * How the matter of the deep substrate responds (design §6, "sustrato material"): it decides the base rock, the strata
 * that the folds expose and which transformations a core born on it prefers.
 */
public enum SubstrateKind {

    /** Classic hellrock: netherrack with red jasper strata. Prefers folding and hollowing. */
    INFERNAL(NetherMat.NETHERRACK, NetherMat.JASPER, NetherMat.NETHERRACK, new double[] { 3, 1, 1, 0.5, 2 }),
    /** Volcanic: basalt with scoria strata. Prefers repetition (columns) and folding. */
    PYRIC(NetherMat.BASALT, NetherMat.SCORIA, NetherMat.BASALT, new double[] { 2, 3, 1, 0.5, 0.5 }),
    /** Soul-bearing: netherrack and tuff, soul sand floors. Prefers hollowing and growth. */
    SOUL(NetherMat.NETHERRACK, NetherMat.TUFF, NetherMat.SOUL_SAND, new double[] { 0.5, 0.5, 1, 2, 2.5 }),
    /** Crystalline: netherrack with chalcedony strata, quartz-rich. Prefers growth and suspension. */
    CRYSTAL(NetherMat.NETHERRACK, NetherMat.CHALCEDONY, NetherMat.NETHERRACK, new double[] { 0.5, 1, 2.5, 3, 1 });

    public static final SubstrateKind[] VALUES = values();

    public final NetherMat base, band, floor;
    /** Preference of a core born on this substrate for each {@link Transform} (unnormalised). */
    final double[] transformBias;

    SubstrateKind(NetherMat base, NetherMat band, NetherMat floor, double[] transformBias) {
        this.base = base;
        this.band = band;
        this.floor = floor;
        this.transformBias = transformBias;
    }
}
