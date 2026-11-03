package net.star1mp.damagetester.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.star1mp.damagetester.DamageTester;

/** Draws glowing parts (like spider eyes) that stay bright in the dark. */
public class GlowLayer extends RenderLayer<DamageTesterRenderState, DamageTesterModel> {
    public static final Identifier JACK_GLOW = DamageTester.id("textures/entity/damage_tester_jack_glow.png");

    public GlowLayer(RenderLayerParent<DamageTesterRenderState, DamageTesterModel> parent) {
        super(parent);
    }

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, DamageTesterRenderState state, float yRot, float xRot) {
        if (state.glowTexture == null || state.isInvisible) {
            return;
        }
        collector.submitModel(this.getParentModel(), state, poseStack, RenderTypes.eyes(state.glowTexture),
                LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, -1, null, state.outlineColor);
    }
}
