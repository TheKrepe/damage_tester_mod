package net.star1mp.damagetester.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;

public class DamageTesterRenderState extends HumanoidRenderState {
    /** Lean back/forward angle in radians (pivot: top of the base plate). */
    public float wobble;
    /** Small side-to-side sway in radians. */
    public float sway;
    public final List<NumberRenderData> numbers = new ArrayList<>();

    public record NumberRenderData(String text, int color, float age, int slot, boolean critical) {
    }
}
