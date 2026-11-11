package net.star1mp.damagetester.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.star1mp.damagetester.DamageTester;
import net.star1mp.damagetester.entity.DamageTesterEntity;

/**
 * Draws the sack cloth in a dye color (or the jeb_ rainbow). The main texture has the cloth cut
 * out in that case, so the two never overlap.
 */
public class ClothColorLayer extends RenderLayer<DamageTesterEntity, DamageTesterModel> {
    public static final ResourceLocation CLOTH_TEXTURE = DamageTester.id("textures/entity/damage_tester_cloth.png");

    public ClothColorLayer(RenderLayerParent<DamageTesterEntity, DamageTesterModel> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffers, int light, DamageTesterEntity entity,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        int color = TesterSkins.clothColor(entity, partialTick);
        if (color == 0 || entity.isInvisible()) {
            return;
        }
        renderColoredCutoutModel(this.getParentModel(), CLOTH_TEXTURE, poseStack, buffers, light, entity, color);
    }
}
