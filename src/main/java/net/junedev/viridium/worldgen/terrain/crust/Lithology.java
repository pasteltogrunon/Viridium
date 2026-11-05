package net.junedev.viridium.worldgen.terrain.crust;

/**
 * Rock types of the 3D crust. {@link #block} is the name of the matching {@code ViriBlocks} field (Viridium rock
 * block); the Minecraft side resolves it, falling back to stone. Colours are for the map's cross-section.
 */
public enum Lithology {

    // sedimentary
    SANDSTONE("sandstone", 0xD8A868),
    QUARTZ_ARENITE("quartz_arenite", 0xEADBC0),
    ARKOSE("arkose", 0xC88E6E),
    CONGLOMERATE("conglomerate", 0xA08A70),
    MUDSTONE("mudstone", 0x8C7666),
    SHALE("shale", 0x76665E),
    BLACK_SHALE("black_shale", 0x3A3634),
    LIMESTONE("limestone", 0xDECE9E),
    DOLOSTONE("dolostone", 0xCDBEA0),
    CHALK("chalk", 0xF2F0E4),
    CHERT("chert", 0x9A6A56),
    EVAPORITE("evaporite", 0xEAE2E6),
    GREYWACKE("greywacke", 0x707468),
    IRONSTONE("ironstone", 0x8A4A30),
    // volcanic
    BASALT("basalt", 0x3A3A44),
    SCORIA("scoria", 0x5A2E2A),
    ANDESITE("andesite", 0x8A8A91),
    RHYOLITE("rhyolite", 0xC8A0A0),
    TUFF("tuff", 0x96A276),
    BRECCIA("breccia", 0x8E7C70),
    PUMICE("pumice", 0xE0DCD0),
    // plutonic
    PINK_GRANITE("pink_granite", 0xD4A09A),
    RED_GRANITE("red_granite", 0xB8705E),
    BLACK_GRANITE("black_granite", 0x4A4648),
    GRANODIORITE("granodiorite", 0xB4B0AA),
    DIORITE("diorite", 0xD4D4CE),
    GABBRO("gabbro", 0x30343A),
    DOLERITE("dolerite", 0x44484C),
    PEGMATITE("pegmatite", 0xEEE6DA),
    KIMBERLITE("kimberlite", 0x4E5A4A),
    // metamorphic
    PHYLLITE("phyllite", 0x6E7880),
    MICASCHIST("micaschist", 0x9C9A8A),
    GREENSCHIST("greenschist", 0x5E7A5A),
    BLUESCHIST("blueschist", 0x4A5E86),
    AMPHIBOLITE("amphibolite", 0x2E3A34),
    GNEISS("gneiss", 0xAA8AA0),
    GRANITIC_GNEISS("granitic_gneiss", 0xBC9C9C),
    MIGMATITE("migmatite", 0x8E6E74),
    MARBLE("marble", 0xEEEAE2),
    QUARTZITE("quartzite", 0xE4DCD8),
    ECLOGITE("eclogite", 0x6E3E3E),
    SERPENTINITE("serpentinite", 0x3E6A4E),
    // mantle
    PERIDOTITE("peridotite", 0x6E8040),
    CHROMITITE("chromitite", 0x2A2A2E);

    public static final Lithology[] VALUES = values();

    public final String block;
    public final int color;

    Lithology(String block, int color) {
        this.block = block;
        this.color = color;
    }
}
