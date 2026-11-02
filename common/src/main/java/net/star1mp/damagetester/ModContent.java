package net.star1mp.damagetester;

import java.util.function.Supplier;
import net.minecraft.core.Direction;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.DefaultDispenseItemBehavior;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.star1mp.damagetester.entity.DamageTesterEntity;
import net.star1mp.damagetester.item.DamageTesterItem;
import net.star1mp.damagetester.platform.Services;

/** All registered content of the mod. */
public final class ModContent {
    public static final String DAMAGE_TESTER_NAME = "damage_tester";

    public static final ResourceKey<EntityType<?>> DAMAGE_TESTER_ENTITY_KEY =
            ResourceKey.create(Registries.ENTITY_TYPE, DamageTester.id(DAMAGE_TESTER_NAME));
    public static final ResourceKey<Item> DAMAGE_TESTER_ITEM_KEY =
            ResourceKey.create(Registries.ITEM, DamageTester.id(DAMAGE_TESTER_NAME));

    /** Vanilla's creative tab keys are private, so we build the key ourselves. */
    public static final ResourceKey<CreativeModeTab> COMBAT_TAB =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("combat"));

    public static final Supplier<EntityType<DamageTesterEntity>> DAMAGE_TESTER = Services.PLATFORM.register(
            BuiltInRegistries.ENTITY_TYPE, DAMAGE_TESTER_NAME,
            () -> EntityType.Builder.<DamageTesterEntity>of(DamageTesterEntity::new, MobCategory.MISC)
                    .sized(0.6F, 1.9F)
                    .eyeHeight(1.55F)
                    .clientTrackingRange(10)
                    .build(DAMAGE_TESTER_ENTITY_KEY));

    public static final Supplier<Item> DAMAGE_TESTER_ITEM = Services.PLATFORM.register(
            BuiltInRegistries.ITEM, DAMAGE_TESTER_NAME,
            () -> new DamageTesterItem(new Item.Properties().stacksTo(16).setId(DAMAGE_TESTER_ITEM_KEY)));

    private ModContent() {
    }

    /** Forces class loading so the registrations above are queued. */
    public static void init() {
    }

    /** Must run after registries are populated (common setup). */
    public static void registerDispenserBehavior() {
        DispenserBlock.registerBehavior(DAMAGE_TESTER_ITEM.get(), new DefaultDispenseItemBehavior() {
            @Override
            protected ItemStack execute(BlockSource source, ItemStack stack) {
                Direction facing = source.state().getValue(DispenserBlock.FACING);
                ServerLevel level = source.level();
                var target = source.pos().relative(facing);
                Vec3 pos = Vec3.atBottomCenterOf(target);
                AABB box = DAMAGE_TESTER.get().getDimensions().makeBoundingBox(pos.x(), pos.y(), pos.z());
                if (!level.noCollision(null, box) || !level.getEntities(null, box).isEmpty()) {
                    return super.execute(source, stack);
                }
                DamageTesterEntity tester = DamageTesterEntity.spawnFromItem(level, stack, null, pos, facing.toYRot());
                if (tester != null) {
                    stack.shrink(1);
                }
                return stack;
            }
        });
    }
}
