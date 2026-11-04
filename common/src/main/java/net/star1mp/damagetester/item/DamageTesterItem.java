package net.star1mp.damagetester.item;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.star1mp.damagetester.ModContent;
import net.star1mp.damagetester.entity.DamageTesterEntity;
import net.star1mp.damagetester.entity.TesterVariant;

/**
 * Places a Damage Tester facing the player. Hanging testers go on the underside of a block,
 * all others on top of one.
 */
public class DamageTesterItem extends Item {
    private final TesterVariant variant;

    public DamageTesterItem(TesterVariant variant, Properties properties) {
        super(properties);
        this.variant = variant;
    }

    public TesterVariant getVariant() {
        return this.variant;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Vec3 spawnPos;
        if (this.variant.ridesMinecart()) {
            BlockPos railPos = context.getClickedPos();
            if (!net.minecraft.world.level.block.BaseRailBlock.isRail(level.getBlockState(railPos))) {
                return InteractionResult.FAIL;
            }
            ItemStack stack = context.getItemInHand();
            if (level instanceof ServerLevel serverLevel) {
                float yaw = Mth.floor((Mth.wrapDegrees(context.getRotation() - 180.0F) + 45.0F) / 90.0F) * 90.0F;
                if (DamageTesterEntity.spawnInMinecart(serverLevel, railPos, stack, context.getPlayer(), yaw) == null) {
                    return InteractionResult.FAIL;
                }
            }
            stack.shrink(1);
            return InteractionResult.SUCCESS;
        }
        if (this.variant.hangs()) {
            if (context.getClickedFace() != Direction.DOWN) {
                return InteractionResult.FAIL;
            }
            BlockPos support = context.getClickedPos();
            BlockState supportState = level.getBlockState(support);
            if (!supportState.isFaceSturdy(level, support, Direction.DOWN)) {
                return InteractionResult.FAIL;
            }
            spawnPos = new Vec3(support.getX() + 0.5,
                    support.getY() - TesterVariant.ROPE_LENGTH - this.variant.height(), support.getZ() + 0.5);
        } else {
            if (context.getClickedFace() == Direction.DOWN) {
                return InteractionResult.FAIL;
            }
            BlockPos pos = new BlockPlaceContext(context).getClickedPos();
            spawnPos = Vec3.atBottomCenterOf(pos);
        }
        AABB box = ModContent.TESTER_TYPES.get(this.variant).get().getDimensions()
                .makeBoundingBox(spawnPos.x(), spawnPos.y(), spawnPos.z());
        if (!level.noCollision(null, box) || !level.getEntities(null, box).isEmpty()) {
            return InteractionResult.FAIL;
        }
        ItemStack stack = context.getItemInHand();
        if (level instanceof ServerLevel serverLevel) {
            // Face the player, snapped to 22.5° steps like an armor stand / sign.
            float yaw = Mth.floor((Mth.wrapDegrees(context.getRotation() - 180.0F) + 11.25F) / 22.5F) * 22.5F;
            DamageTesterEntity tester = DamageTesterEntity.spawnFromItem(this.variant, serverLevel, stack, context.getPlayer(), spawnPos, yaw);
            if (tester == null) {
                return InteractionResult.FAIL;
            }
        }
        stack.shrink(1);
        return InteractionResult.SUCCESS;
    }
}
