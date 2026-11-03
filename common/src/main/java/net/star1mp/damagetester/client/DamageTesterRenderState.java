package net.star1mp.damagetester.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;
import net.star1mp.damagetester.entity.TesterVariant;

public class DamageTesterRenderState extends HumanoidRenderState {
    /** Lean back/forward angle in radians (pivot: top of the base plate). */
    public float wobble;
    /** Small side-to-side sway in radians. */
    public float sway;
    public final List<NumberRenderData> numbers = new ArrayList<>();
    public TesterVariant variant = TesterVariant.STANDARD;
    /** Main texture (depends on the skin). */
    public Identifier texture = DamageTesterRenderer.TEXTURE;
    /** ARGB color of the cloth overlay, or 0 when the cloth is part of the main texture. */
    public int clothColor;
    /** Optional emissive texture drawn full-bright on top (e.g. the jack o'lantern's glowing face). */
    public Identifier glowTexture;

    public record NumberRenderData(String text, int color, float age, int slot, boolean critical) {
    }
}
