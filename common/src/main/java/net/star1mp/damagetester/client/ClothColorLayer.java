package net.star1mp.damagetester.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.star1mp.damagetester.DamageTester;

/**
 * Draws the sack cloth in a dye color (or the jeb_ rainbow). The main texture has the cloth cut
 * out in that case, so the two never overlap.
 */
public class ClothColorLayer extends RenderLayer<DamageTesterRenderState, DamageTesterModel> {
    public static final Identifier CLOTH_TEXTURE = DamageTester.id("textures/entity/damage_tester_cloth.png");

    public ClothColorLayer(RenderLayerParent<DamageTesterRenderState, DamageTesterModel> parent) {
        super(parent);
    }

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, DamageTesterRenderState state, float yRot, float xRot) {
        if (state.clothColor == 0 || state.isInvisible) {
            return;
        }
        collector.submitModel(this.getParentModel(), state, poseStack, RenderTypes.entityCutout(CLOTH_TEXTURE),
                light, LivingEntityRenderer.getOverlayCoords(state, 0.0F), state.clothColor, null, state.outlineColor);
    }
}
