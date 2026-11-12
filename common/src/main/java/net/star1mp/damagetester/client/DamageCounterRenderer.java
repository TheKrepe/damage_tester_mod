package net.star1mp.damagetester.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.star1mp.damagetester.DamageTester;
import net.star1mp.damagetester.block.DamageCounterBlock;
import net.star1mp.damagetester.block.DamageCounterBlockEntity;
import net.star1mp.damagetester.entity.DamageTesterEntity;

/** Draws the statistics as glowing text on the counter's screen. */
public class DamageCounterRenderer implements BlockEntityRenderer<DamageCounterBlockEntity> {
    private static final DecimalFormat FORMAT = new DecimalFormat("0.#", DecimalFormatSymbols.getInstance(Locale.ROOT));
    private static final int TITLE_COLOR = 0xFFFFC94A;
    private static final int TEXT_COLOR = 0xFFE8F4FF;
    private static final int DIM_COLOR = 0xFF8FA3B8;

    private final Font font;

    public DamageCounterRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.getFont();
    }

    public record Line(FormattedCharSequence text, int color) {
    }

    private static List<Line> lines(DamageCounterBlockEntity blockEntity) {
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(Component.translatable("block.damage_tester.damage_counter").getVisualOrderText(), TITLE_COLOR));
        DamageTesterEntity.Stats stats = blockEntity.getStats();
        if (!blockEntity.hasTester()) {
            if (net.star1mp.damagetester.SelfTestHook.ENABLED) {
                net.star1mp.damagetester.SelfTestHook.clientState("counter pos=" + blockEntity.getBlockPos().toShortString(), "empty");
            }
            lines.add(new Line(Component.translatable("counter.damage_tester.no_tester").getVisualOrderText(), DIM_COLOR));
            return lines;
        }
        if (!stats.hasData()) {
            lines.add(new Line(Component.translatable("counter.damage_tester.waiting").getVisualOrderText(), DIM_COLOR));
            return lines;
        }
        int valueColor = stats.active() ? TEXT_COLOR : DIM_COLOR;
        lines.add(line("counter.damage_tester.last", FORMAT.format(stats.lastHit()), valueColor));
        lines.add(line("counter.damage_tester.dps", FORMAT.format(stats.dps()), valueColor));
        lines.add(line("counter.damage_tester.total", FORMAT.format(stats.total()), valueColor));
        lines.add(line("counter.damage_tester.hits", String.valueOf(stats.hits()), valueColor));
        lines.add(line("counter.damage_tester.max", FORMAT.format(stats.maxHit()), valueColor));
        if (net.star1mp.damagetester.SelfTestHook.ENABLED) {
            net.star1mp.damagetester.SelfTestHook.clientState("counter pos=" + blockEntity.getBlockPos().toShortString(),
                    "hits=" + stats.hits() + " total=" + FORMAT.format(stats.total()) + " active=" + stats.active());
        }
        return lines;
    }

    private static Line line(String key, String value, int color) {
        return new Line(Component.translatable(key, value).getVisualOrderText(), color);
    }

    @Override
    public void render(DamageCounterBlockEntity blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource buffers,
                       int light, int overlay) {
        try {
            var blockState = blockEntity.getBlockState();
            Direction facing = blockState.hasProperty(DamageCounterBlock.FACING) ? blockState.getValue(DamageCounterBlock.FACING) : Direction.NORTH;
            List<Line> lines = lines(blockEntity);
            poseStack.pushPose();
            poseStack.translate(0.5F, 0.5F, 0.5F);
            poseStack.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
            // Just in front of the screen face (the block's south side after rotation).
            poseStack.translate(0.0F, 0.0F, 0.5F + 0.002F);
            float scale = 1.0F / 100.0F;
            poseStack.scale(scale, -scale, scale);
            int lineHeight = this.font.lineHeight + 1;
            float top = -(lines.size() * lineHeight) / 2.0F;
            for (int i = 0; i < lines.size(); i++) {
                Line line = lines.get(i);
                float x = -this.font.width(line.text()) / 2.0F;
                this.font.drawInBatch(line.text(), x, top + i * lineHeight, line.color(), false, poseStack.last().pose(), buffers,
                        Font.DisplayMode.POLYGON_OFFSET, 0, LightTexture.FULL_BRIGHT);
            }
            poseStack.popPose();
        } catch (RuntimeException e) {
            DamageTester.logRenderError("damage counter", e);
        }
    }
}
