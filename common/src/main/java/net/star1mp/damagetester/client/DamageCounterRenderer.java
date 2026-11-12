package net.star1mp.damagetester.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import net.star1mp.damagetester.DamageTester;
import net.star1mp.damagetester.block.DamageCounterBlock;
import net.star1mp.damagetester.block.DamageCounterBlockEntity;
import net.star1mp.damagetester.entity.DamageTesterEntity;

/** Draws the statistics as glowing text on the counter's screen. */
public class DamageCounterRenderer implements BlockEntityRenderer<DamageCounterBlockEntity, DamageCounterRenderer.State> {
    private static final DecimalFormat FORMAT = new DecimalFormat("0.#", DecimalFormatSymbols.getInstance(Locale.ROOT));
    private static final int TITLE_COLOR = 0xFFFFC94A;
    private static final int TEXT_COLOR = 0xFFE8F4FF;
    private static final int DIM_COLOR = 0xFF8FA3B8;

    private final Font font;

    public DamageCounterRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.font();
    }

    public static class State extends BlockEntityRenderState {
        public Direction facing = Direction.NORTH;
        public final List<Line> lines = new ArrayList<>();
    }

    public record Line(FormattedCharSequence text, int color) {
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(DamageCounterBlockEntity blockEntity, State state, float partialTick, Vec3 cameraPos,
                                   ModelFeatureRenderer.CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(blockEntity, state, partialTick, cameraPos, breakProgress);
        {
            var blockState = blockEntity.getBlockState();
            state.facing = blockState.hasProperty(DamageCounterBlock.FACING) ? blockState.getValue(DamageCounterBlock.FACING) : Direction.NORTH;
        }
        state.lines.clear();
        state.lines.add(new Line(Component.translatable("block.damage_tester.damage_counter").getVisualOrderText(), TITLE_COLOR));
        DamageTesterEntity.Stats stats = blockEntity.getStats();
        if (!blockEntity.hasTester()) {
            state.lines.add(new Line(Component.translatable("counter.damage_tester.no_tester").getVisualOrderText(), DIM_COLOR));
            return;
        }
        if (!stats.hasData()) {
            state.lines.add(new Line(Component.translatable("counter.damage_tester.waiting").getVisualOrderText(), DIM_COLOR));
            return;
        }
        int valueColor = stats.active() ? TEXT_COLOR : DIM_COLOR;
        state.lines.add(line("counter.damage_tester.last", FORMAT.format(stats.lastHit()), valueColor));
        state.lines.add(line("counter.damage_tester.dps", FORMAT.format(stats.dps()), valueColor));
        state.lines.add(line("counter.damage_tester.total", FORMAT.format(stats.total()), valueColor));
        state.lines.add(line("counter.damage_tester.hits", String.valueOf(stats.hits()), valueColor));
        state.lines.add(line("counter.damage_tester.max", FORMAT.format(stats.maxHit()), valueColor));
    }

    private static Line line(String key, String value, int color) {
        return new Line(Component.translatable(key, value).getVisualOrderText(), color);
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        try {
            poseStack.pushPose();
            poseStack.translate(0.5F, 0.5F, 0.5F);
            poseStack.mulPose(Axis.YP.rotationDegrees(-state.facing.toYRot()));
            // Just in front of the screen face (the block's south side after rotation).
            poseStack.translate(0.0F, 0.0F, 0.5F + 0.002F);
            float scale = 1.0F / 100.0F;
            poseStack.scale(scale, -scale, scale);
            int lineHeight = this.font.lineHeight + 1;
            float top = -(state.lines.size() * lineHeight) / 2.0F;
            for (int i = 0; i < state.lines.size(); i++) {
                Line line = state.lines.get(i);
                float x = -this.font.width(line.text()) / 2.0F;
                collector.submitText(poseStack, x, top + i * lineHeight, line.text(), false, Font.DisplayMode.POLYGON_OFFSET,
                        LightCoordsUtil.FULL_BRIGHT, line.color(), 0, 0);
            }
            poseStack.popPose();
        } catch (RuntimeException e) {
            DamageTester.LOG.debug("Failed to draw damage counter", e);
        }
    }
}
