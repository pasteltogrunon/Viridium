package net.junedev.viridium.worldgen.nether;

/**
 * Materials produced by the Nether generator, independent of Minecraft blocks (so headless tests can render them).
 * {@link NetherBlocks} resolves each one to a block and metadata.
 */
public enum NetherMat {

    AIR,
    BEDROCK,
    LAVA,
    NETHERRACK,
    BASALT,
    SCORIA,
    OBSIDIAN,
    PUMICE,
    TUFF,
    JASPER,
    CHALCEDONY,
    PERIDOTITE,
    KOMATIITE,
    SOUL_SAND,
    /** Ash: loose cinder floors near active lava. */
    ASH,
    QUARTZ_ORE,
    /** Bone-like mineral (ossuaries, growth). */
    BONE,
    GLOWSTONE,
    /** Compacted path of the highways. */
    PATH,
    BROWN_MUSHROOM,
    RED_MUSHROOM,
    NETHER_WART,
    FIRE;

    public static final NetherMat[] VALUES = values();

    public boolean solid() {
        return this != AIR && this != LAVA
            && this != BROWN_MUSHROOM
            && this != RED_MUSHROOM
            && this != NETHER_WART
            && this != FIRE;
    }

    public byte id() {
        return (byte) ordinal();
    }
}
