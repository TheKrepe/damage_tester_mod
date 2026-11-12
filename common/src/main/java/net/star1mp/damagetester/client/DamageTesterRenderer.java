package net.star1mp.damagetester.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.star1mp.damagetester.DamageTester;
import net.star1mp.damagetester.config.DamageTesterConfig;
import net.star1mp.damagetester.entity.DamageTesterEntity;
import net.star1mp.damagetester.entity.TesterVariant;

public class DamageTesterRenderer extends LivingEntityRenderer<DamageTesterEntity, DamageTesterRenderState, DamageTesterModel> {
    public static final Identifier TEXTURE = DamageTester.id("textures/entity/damage_tester.png");
    private static final DecimalFormat FORMAT = new DecimalFormat("0.#", DecimalFormatSymbols.getInstance(Locale.ROOT));
    /** Horizontal offsets (in blocks) cycled through so consecutive numbers do not overlap. */
    private static final float[] SLOT_OFFSETS = {0.0F, -0.32F, 0.32F, -0.16F, 0.16F, -0.42F, 0.42F};
    /** Height of the top of the base plate in blocks, the pivot for the wobble. */
    private static final float PIVOT_Y = 2.0F / 16.0F;

    public static final Identifier TEXTURE_JACK = DamageTester.id("textures/entity/damage_tester_jack.png");
    public static final Identifier TEXTURE_STRAW = DamageTester.id("textures/entity/damage_tester_straw.png");
    /** Default texture with the cloth cut out; the cloth is drawn by {@link ClothColorLayer} in a dye color. */
    public static final Identifier TEXTURE_NO_CLOTH = DamageTester.id("textures/entity/damage_tester_no_cloth.png");

    private final Model<DamageTesterRenderState> baseModel;
    private final Font font;

    public DamageTesterRenderer(EntityRendererProvider.Context context) {
        super(context, new DamageTesterModel(DamageTesterModel.createBodyLayer().bakeRoot()), 0.0F); // no shadow
        this.baseModel = new Model<>(DamageTesterModel.createBaseLayer().bakeRoot(), RenderTypes::entityCutout) {
        };
        this.font = context.getFont();

        ArmorModelSet<DamageTesterArmorModel> armorModels = HumanoidModel
                .createArmorMeshSet(new CubeDeformation(0.5F), new CubeDeformation(1.0F))
                .map(mesh -> new DamageTesterArmorModel(LayerDefinition.create(mesh, 64, 32).bakeRoot()));
        this.addLayer(new ClothColorLayer(this));
        this.addLayer(new GlowLayer(this));
        this.addLayer(new HumanoidArmorLayer<>(this, armorModels, context.getEquipmentRenderer()));
        this.addLayer(new CustomHeadLayer<>(this, context.getModelSet(), context.getPlayerSkinRenderCache()));
    }

    @Override
    public DamageTesterRenderState createRenderState() {
        return new DamageTesterRenderState();
    }

    @Override
    public Identifier getTextureLocation(DamageTesterRenderState state) {
        return state.texture;
    }

    @Override
    protected boolean shouldShowName(DamageTesterEntity entity, double distanceToCameraSq) {
        // Only show a name tag for testers that were actually named (like armor stands).
        return entity.hasCustomName() && super.shouldShowName(entity, distanceToCameraSq);
    }

    @Override
    public void extractRenderState(DamageTesterEntity entity, DamageTesterRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        HumanoidMobRenderer.extractHumanoidRenderState(entity, state, partialTick, this.itemModelResolver);
        state.hasRedOverlay = false;
        state.wobble = entity.getWobble(partialTick);
        state.sway = entity.getSway(partialTick);
        state.variant = entity.getVariant();
        TesterSkins.apply(entity, state);
        if (net.star1mp.damagetester.SelfTestHook.ENABLED) {
            int armor = 0;
            for (net.minecraft.world.entity.EquipmentSlot slot : new net.minecraft.world.entity.EquipmentSlot[]{
                    net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
                    net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET}) {
                if (!entity.getItemBySlot(slot).isEmpty()) {
                    armor++;
                }
            }
            net.star1mp.damagetester.SelfTestHook.clientState("render id=" + entity.getId(), "variant=" + entity.getVariant().id()
                    + " skin=" + entity.getSkin() + " texture=" + state.texture + " armor=" + armor
                    + " name=" + (entity.hasCustomName() ? entity.getName().getString() : "-"));
        }

        state.numbers.clear();
        boolean hearts = DamageTesterConfig.get().damageInHearts;
        for (DamageTesterEntity.FloatingNumber number : entity.getFloatingNumbers()) {
            float age = entity.tickCount - number.spawnTick() + partialTick;
            if (age < 0.0F || age > DamageTesterEntity.NUMBER_LIFETIME) {
                continue;
            }
            float value = hearts ? number.amount() / 2.0F : number.amount();
            String text = FORMAT.format(value) + (hearts ? "❤" : "");
            if (number.critical()) {
                text = "✦" + text;
            }
            state.numbers.add(new DamageTesterRenderState.NumberRenderData(text, number.color(), age, number.slot(), number.critical()));
            if (net.star1mp.damagetester.SelfTestHook.ENABLED) {
                net.star1mp.damagetester.SelfTestHook.clientState("number id=" + entity.getId() + " tick=" + number.spawnTick() + " slot=" + number.slot(), "text=" + text);
            }
        }
    }

    @Override
    protected void setupRotations(DamageTesterRenderState state, PoseStack poseStack, float bodyRot, float scale) {
        super.setupRotations(state, poseStack, bodyRot, scale);
        float pivot = PIVOT_Y;
        if (state.variant == TesterVariant.HANGING) {
            // No legs: lift the hips to the bottom of the hitbox and swing from the rope's top end.
            poseStack.translate(0.0F, -0.75F, 0.0F);
            pivot = 2.0F + TesterVariant.ROPE_LENGTH;
        } else if (state.variant == TesterVariant.SMALL) {
            pivot = PIVOT_Y * SMALL_SCALE;
        }
        // Lean around the pivot. Model space here is still "Y up".
        if (state.wobble != 0.0F || state.sway != 0.0F) {
            poseStack.translate(0.0F, pivot, 0.0F);
            poseStack.mulPose(Axis.XP.rotation(state.wobble));
            poseStack.mulPose(Axis.ZP.rotation(state.sway));
            poseStack.translate(0.0F, -pivot, 0.0F);
        }
    }

    public static final float SMALL_SCALE = 0.5F;

    @Override
    protected void scale(DamageTesterRenderState state, PoseStack poseStack) {
        if (state.variant == TesterVariant.SMALL) {
            poseStack.scale(SMALL_SCALE, SMALL_SCALE, SMALL_SCALE);
        }
    }

    @Override
    public void submit(DamageTesterRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        super.submit(state, poseStack, collector, camera);
        try {
            this.submitBase(state, poseStack, collector);
        } catch (RuntimeException e) {
            DamageTester.logRenderError("damage tester base", e);
        }
        try {
            this.submitNumbers(state, poseStack, collector, camera);
        } catch (RuntimeException e) {
            DamageTester.logRenderError("damage numbers", e);
        }
    }

    /** The plank base does not wobble, so it is drawn outside of the tilted model. */
    private void submitBase(DamageTesterRenderState state, PoseStack poseStack, SubmitNodeCollector collector) {
        if (state.variant == TesterVariant.HANGING || state.variant == TesterVariant.MINECART) {
            return; // hangs on a rope / stands in the cart
        }
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - state.bodyRot));
        poseStack.scale(-1.0F, -1.0F, 1.0F);
        this.scale(state, poseStack);
        poseStack.translate(0.0F, -1.501F, 0.0F);
        collector.submitModel(this.baseModel, state, poseStack, RenderTypes.entityCutout(state.texture),
                state.lightCoords, OverlayTexture.NO_OVERLAY, -1, null, state.outlineColor, null);
        poseStack.popPose();
    }

    private void submitNumbers(DamageTesterRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.numbers.isEmpty()) {
            return;
        }
        float lifetime = DamageTesterEntity.NUMBER_LIFETIME;
        // Keep numbers readable from further away (like a name tag would be).
        float distance = (float) Math.sqrt(state.distanceToCameraSq);
        float distanceScale = Mth.clamp(distance / 6.0F, 1.0F, 3.0F);
        for (DamageTesterRenderState.NumberRenderData number : state.numbers) {
            float progress = Mth.clamp(number.age() / lifetime, 0.0F, 1.0F);
            float rise = easeOutCubic(Math.min(1.0F, number.age() / 18.0F));
            float pop = easeOutBack(Math.min(1.0F, number.age() / 6.0F));
            float fade = progress > 0.75F ? 1.0F - (progress - 0.75F) / 0.25F : 1.0F;
            int alpha = Mth.clamp((int) (fade * 255.0F), 8, 255);
            float sideways = SLOT_OFFSETS[Math.floorMod(number.slot(), SLOT_OFFSETS.length)];
            float scale = 0.026F * distanceScale * pop * (number.critical() ? 1.3F : 1.0F);

            poseStack.pushPose();
            // Start just above the head and float upwards.
            poseStack.translate(0.0F, state.boundingBoxHeight + 0.15F + rise * 0.7F, 0.0F);
            poseStack.mulPose(camera.orientation);
            poseStack.translate(sideways * (0.5F + rise * 0.7F) * distanceScale, 0.0F, 0.0F);
            poseStack.scale(scale, -scale, scale);

            FormattedCharSequence text = Component.literal(number.text()).getVisualOrderText();
            float x = -this.font.width(text) / 2.0F;
            int color = ARGB.color(alpha, number.critical() ? DamageColors.CRITICAL : number.color());
            collector.submitText(poseStack, x, 0.0F, text, true, Font.DisplayMode.NORMAL,
                    LightCoordsUtil.FULL_BRIGHT, color, 0, 0);
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
