package net.star1mp.damagetester.entity;

/** The different kinds of damage testers. Each one is its own entity type and item. */
public enum TesterVariant {
    /** Stands on the ground on a plank base. */
    STANDARD("damage_tester", 0.6F, 1.9F, 1.55F),
    /** Child-sized, for checking damage against small mobs. */
    SMALL("small_damage_tester", 0.35F, 0.98F, 0.8F),
    /** Hangs on a rope from the underside of a block, like a punching bag. */
    HANGING("hanging_damage_tester", 0.6F, 1.25F, 1.0F),
    /** Rolls around on a little cart, for target practice with bows and tridents. */
    MOVING("moving_damage_tester", 0.6F, 1.9F, 1.55F);

    private final String id;
    private final float width;
    private final float height;
    private final float eyeHeight;

    TesterVariant(String id, float width, float height, float eyeHeight) {
        this.id = id;
        this.width = width;
        this.height = height;
        this.eyeHeight = eyeHeight;
    }

    public String id() {
        return this.id;
    }

    public float width() {
        return this.width;
    }

    public float height() {
        return this.height;
    }

    public float eyeHeight() {
        return this.eyeHeight;
    }

    /** Hanging testers are placed against the underside of a block, everything else on top. */
    public boolean hangs() {
        return this == HANGING;
    }

    /** Length of the rope between the block and the top of a hanging tester's head, in blocks. */
    public static final float ROPE_LENGTH = 0.25F;
}
