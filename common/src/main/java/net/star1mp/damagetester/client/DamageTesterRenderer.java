package net.star1mp.damagetester.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.star1mp.damagetester.DamageTester;
import net.star1mp.damagetester.config.DamageTesterConfig;
import net.star1mp.damagetester.entity.DamageTesterEntity;
import net.star1mp.damagetester.entity.TesterVariant;

public class DamageTesterRenderer extends LivingEntityRenderer<DamageTesterEntity, DamageTesterModel> {
    public static final ResourceLocation TEXTURE = DamageTester.id("textures/entity/damage_tester.png");
    private static final DecimalFormat FORMAT = new DecimalFormat("0.#", DecimalFormatSymbols.getInstance(Locale.ROOT));
    /** Horizontal offsets (in blocks) cycled through so consecutive numbers do not overlap. */
    private static final float[] SLOT_OFFSETS = {0.0F, -0.32F, 0.32F, -0.16F, 0.16F, -0.42F, 0.42F};
    /** Height of the top of the base plate in blocks, the pivot for the wobble. */
    private static final float PIVOT_Y = 2.0F / 16.0F;
    public static final float SMALL_SCALE = 0.5F;

    public static final ResourceLocation TEXTURE_JACK = DamageTester.id("textures/entity/damage_tester_jack.png");
    public static final ResourceLocation TEXTURE_STRAW = DamageTester.id("textures/entity/damage_tester_straw.png");
    /** Default texture with the cloth cut out; the cloth is drawn by {@link ClothColorLayer} in a dye color. */
    public static final ResourceLocation TEXTURE_NO_CLOTH = DamageTester.id("textures/entity/damage_tester_no_cloth.png");

    private final ModelPart baseModel;
    private final Font font;

    public DamageTesterRenderer(EntityRendererProvider.Context context) {
        super(context, new DamageTesterModel(DamageTesterModel.createBodyLayer().bakeRoot()), 0.0F); // no shadow
        this.baseModel = DamageTesterModel.createBaseLayer().bakeRoot();
        this.font = context.getFont();

        HumanoidModel<DamageTesterEntity> inner = new HumanoidModel<>(
                LayerDefinition.create(HumanoidModel.createMesh(new CubeDeformation(0.5F), 0.0F), 64, 32).bakeRoot());
        HumanoidModel<DamageTesterEntity> outer = new HumanoidModel<>(
                LayerDefinition.create(HumanoidModel.createMesh(new CubeDeformation(1.0F), 0.0F), 64, 32).bakeRoot());
        this.addLayer(new ClothColorLayer(this));
        this.addLayer(new GlowLayer(this));
        this.addLayer(new HumanoidArmorLayer<>(this, inner, outer, context.getModelManager()));
        this.addLayer(new CustomHeadLayer<>(this, context.getModelSet(), context.getItemInHandRenderer()));
    }

    @Override
    public ResourceLocation getTextureLocation(DamageTesterEntity entity) {
        return TesterSkins.texture(entity);
    }

    @Override
    protected boolean shouldShowName(DamageTesterEntity entity) {
        // Only show a name tag for testers that were actually named (like armor stands).
        return entity.hasCustomName() && super.shouldShowName(entity);
    }

    @Override
    protected void setupRotations(DamageTesterEntity entity, PoseStack poseStack, float bob, float bodyRot, float partialTick) {
        super.setupRotations(entity, poseStack, bob, bodyRot, partialTick);
        TesterVariant variant = entity.getVariant();
        float pivot = PIVOT_Y;
        if (variant == TesterVariant.HANGING) {
            // No legs: lift the hips to the bottom of the hitbox and swing from the rope's top end.
            poseStack.translate(0.0F, -0.75F, 0.0F);
            pivot = 2.0F + TesterVariant.ROPE_LENGTH;
        } else if (variant == TesterVariant.SMALL) {
            pivot = PIVOT_Y * SMALL_SCALE;
        }
        float wobble = entity.getWobble(partialTick);
        float sway = entity.getSway(partialTick);
        // Lean around the pivot. Model space here is still "Y up".
        if (wobble != 0.0F || sway != 0.0F) {
            poseStack.translate(0.0F, pivot, 0.0F);
            poseStack.mulPose(Axis.XP.rotation(wobble));
            poseStack.mulPose(Axis.ZP.rotation(sway));
            poseStack.translate(0.0F, -pivot, 0.0F);
        }
    }

    @Override
    protected void scale(DamageTesterEntity entity, PoseStack poseStack, float partialTick) {
        if (entity.getVariant() == TesterVariant.SMALL) {
            poseStack.scale(SMALL_SCALE, SMALL_SCALE, SMALL_SCALE);
        }
    }

    @Override
    public void render(DamageTesterEntity entity, float yaw, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light) {
        super.render(entity, yaw, partialTick, poseStack, buffers, light);
        try {
            this.renderBase(entity, partialTick, poseStack, buffers, light);
        } catch (RuntimeException e) {
            DamageTester.LOG.debug("Failed to draw damage tester base", e);
        }
        try {
            this.renderNumbers(entity, partialTick, poseStack, buffers);
        } catch (RuntimeException e) {
            DamageTester.LOG.debug("Failed to draw damage numbers", e);
        }
    }

    /** The plank base does not wobble, so it is drawn outside of the tilted model. */
    private void renderBase(DamageTesterEntity entity, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light) {
        TesterVariant variant = entity.getVariant();
        if (variant == TesterVariant.HANGING || variant == TesterVariant.MINECART || entity.isInvisible()) {
            return; // hangs on a rope / stands in the cart
        }
        float bodyRot = Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot);
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - bodyRot));
        poseStack.scale(-1.0F, -1.0F, 1.0F);
        this.scale(entity, poseStack, partialTick);
        poseStack.translate(0.0F, -1.501F, 0.0F);
        this.baseModel.render(poseStack, buffers.getBuffer(RenderType.entityCutout(this.getTextureLocation(entity))),
                light, OverlayTexture.NO_OVERLAY);
        poseStack.popPose();
    }

    private void renderNumbers(DamageTesterEntity entity, float partialTick, PoseStack poseStack, MultiBufferSource buffers) {
        if (entity.getFloatingNumbers().isEmpty()) {
            return;
        }
        boolean hearts = DamageTesterConfig.get().damageInHearts;
        float lifetime = DamageTesterEntity.NUMBER_LIFETIME;
        // Keep numbers readable from further away (like a name tag would be).
        float distance = (float) Math.sqrt(this.entityRenderDispatcher.distanceToSqr(entity));
        float distanceScale = Mth.clamp(distance / 6.0F, 1.0F, 3.0F);
        for (DamageTesterEntity.FloatingNumber number : entity.getFloatingNumbers()) {
            float age = entity.tickCount - number.spawnTick() + partialTick;
            if (age < 0.0F || age > lifetime) {
                continue;
            }
            float value = hearts ? number.amount() / 2.0F : number.amount();
            String label = FORMAT.format(value) + (hearts ? "❤" : "");
            if (number.critical()) {
                label = "✦" + label;
            }
            float progress = Mth.clamp(age / lifetime, 0.0F, 1.0F);
            float rise = easeOutCubic(Math.min(1.0F, age / 18.0F));
            float pop = easeOutBack(Math.min(1.0F, age / 6.0F));
            float fade = progress > 0.75F ? 1.0F - (progress - 0.75F) / 0.25F : 1.0F;
            int alpha = Mth.clamp((int) (fade * 255.0F), 8, 255);
            float sideways = SLOT_OFFSETS[Math.floorMod(number.slot(), SLOT_OFFSETS.length)];
            float scale = 0.026F * distanceScale * pop * (number.critical() ? 1.3F : 1.0F);

            poseStack.pushPose();
            // Start just above the head and float upwards.
            poseStack.translate(0.0F, entity.getBbHeight() + 0.15F + rise * 0.7F, 0.0F);
            poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation());
            poseStack.translate(sideways * (0.5F + rise * 0.7F) * distanceScale, 0.0F, 0.0F);
            poseStack.scale(scale, -scale, scale);

            FormattedCharSequence text = Component.literal(label).getVisualOrderText();
            float x = -this.font.width(text) / 2.0F;
            int rgb = number.critical() ? DamageColors.CRITICAL : number.color();
            int color = FastColor.ARGB32.color(alpha, FastColor.ARGB32.red(rgb), FastColor.ARGB32.green(rgb), FastColor.ARGB32.blue(rgb));
            this.font.drawInBatch(text, x, 0.0F, color, true, poseStack.last().pose(), buffers, Font.DisplayMode.NORMAL,
                    0, LightTexture.FULL_BRIGHT);
            poseStack.popPose();
        }
    }

    private static float easeOutCubic(float t) {
        float p = 1.0F - t;
        return 1.0F - p * p * p;
    }

    private static float easeOutBack(float t) {
        float p = t - 1.0F;
        return 1.0F + 2.70158F * p * p * p + 1.70158F * p * p;
    }
}
