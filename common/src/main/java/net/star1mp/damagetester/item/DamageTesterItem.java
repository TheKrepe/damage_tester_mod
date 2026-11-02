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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.star1mp.damagetester.ModContent;
import net.star1mp.damagetester.entity.DamageTesterEntity;

/** Places a Damage Tester on the clicked block, facing the player. */
public class DamageTesterItem extends Item {
    public DamageTesterItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getClickedFace() == Direction.DOWN) {
            return InteractionResult.FAIL;
        }
        Level level = context.getLevel();
        BlockPlaceContext placeContext = new BlockPlaceContext(context);
        BlockPos pos = placeContext.getClickedPos();
        Vec3 bottom = Vec3.atBottomCenterOf(pos);
        AABB box = ModContent.DAMAGE_TESTER.get().getDimensions().makeBoundingBox(bottom.x(), bottom.y(), bottom.z());
        if (!level.noCollision(null, box) || !level.getEntities(null, box).isEmpty()) {
            return InteractionResult.FAIL;
        }
        ItemStack stack = context.getItemInHand();
        if (level instanceof ServerLevel serverLevel) {
            // Face the player, snapped to 22.5° steps like an armor stand / sign.
            float yaw = Mth.floor((Mth.wrapDegrees(context.getRotation() - 180.0F) + 11.25F) / 22.5F) * 22.5F;
            DamageTesterEntity tester = DamageTesterEntity.spawnFromItem(serverLevel, stack, context.getPlayer(), bottom, yaw);
            if (tester == null) {
                return InteractionResult.FAIL;
            }
        }
        stack.shrink(1);
        return InteractionResult.SUCCESS;
    }
}
