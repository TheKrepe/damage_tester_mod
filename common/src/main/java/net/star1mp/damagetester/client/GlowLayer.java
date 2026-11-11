package net.star1mp.damagetester.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.star1mp.damagetester.DamageTester;
import net.star1mp.damagetester.entity.DamageTesterEntity;

/** Draws glowing parts (like spider eyes) that stay bright in the dark. */
public class GlowLayer extends RenderLayer<DamageTesterEntity, DamageTesterModel> {
    public static final ResourceLocation JACK_GLOW = DamageTester.id("textures/entity/damage_tester_jack_glow.png");

    public GlowLayer(RenderLayerParent<DamageTesterEntity, DamageTesterModel> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffers, int light, DamageTesterEntity entity,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        ResourceLocation glow = TesterSkins.glowTexture(entity);
        if (glow == null || entity.isInvisible()) {
            return;
        }
        this.getParentModel().renderToBuffer(poseStack, buffers.getBuffer(RenderType.eyes(glow)),
                LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F);
    }
}
