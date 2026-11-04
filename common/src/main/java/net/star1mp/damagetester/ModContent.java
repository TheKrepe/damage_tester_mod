package net.star1mp.damagetester;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.star1mp.damagetester.block.DamageCounterBlock;
import net.star1mp.damagetester.block.DamageCounterBlockEntity;
import net.star1mp.damagetester.entity.DamageTesterEntity;
import net.star1mp.damagetester.entity.TesterVariant;
import net.star1mp.damagetester.item.DamageTesterItem;
import net.star1mp.damagetester.platform.Services;

/** All registered content of the mod. */
public final class ModContent {
    /** Vanilla's creative tab keys are private, so we build the key ourselves. */
    public static final ResourceKey<CreativeModeTab> COMBAT_TAB = vanillaTab("combat");
    public static final ResourceKey<CreativeModeTab> FUNCTIONAL_TAB = vanillaTab("functional_blocks");
    public static final ResourceKey<CreativeModeTab> REDSTONE_TAB = vanillaTab("redstone_blocks");

    private static ResourceKey<CreativeModeTab> vanillaTab(String name) {
        return ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace(name));
    }

    public static final Map<TesterVariant, Supplier<EntityType<DamageTesterEntity>>> TESTER_TYPES = new EnumMap<>(TesterVariant.class);
    public static final Map<TesterVariant, Supplier<Item>> TESTER_ITEMS = new EnumMap<>(TesterVariant.class);

    static {
        for (TesterVariant variant : TesterVariant.values()) {
            ResourceKey<EntityType<?>> entityKey = ResourceKey.create(Registries.ENTITY_TYPE, DamageTester.id(variant.id()));
            ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, DamageTester.id(variant.id()));
            TESTER_TYPES.put(variant, Services.PLATFORM.register(BuiltInRegistries.ENTITY_TYPE, variant.id(), () -> {
                EntityType.Builder<DamageTesterEntity> builder = EntityType.Builder.<DamageTesterEntity>of(DamageTesterEntity::new, MobCategory.MISC)
                        .sized(variant.width(), variant.height())
                        .eyeHeight(variant.eyeHeight())
                        .clientTrackingRange(10);
                return builder.build(entityKey);
            }));
            TESTER_ITEMS.put(variant, Services.PLATFORM.register(BuiltInRegistries.ITEM, variant.id(),
                    () -> new DamageTesterItem(variant, new Item.Properties().stacksTo(16).setId(itemKey))));
        }
    }

    /** The standard tester (kept as named fields for convenience). */
    public static final Supplier<EntityType<DamageTesterEntity>> DAMAGE_TESTER = TESTER_TYPES.get(TesterVariant.STANDARD);
    public static final Supplier<Item> DAMAGE_TESTER_ITEM = TESTER_ITEMS.get(TesterVariant.STANDARD);

    // ------------------------------------------------------------------ damage counter block
    public static final String DAMAGE_COUNTER_NAME = "damage_counter";
    private static final ResourceKey<Block> DAMAGE_COUNTER_BLOCK_KEY =
            ResourceKey.create(Registries.BLOCK, DamageTester.id(DAMAGE_COUNTER_NAME));
    private static final ResourceKey<Item> DAMAGE_COUNTER_ITEM_KEY =
            ResourceKey.create(Registries.ITEM, DamageTester.id(DAMAGE_COUNTER_NAME));

    public static final Supplier<Block> DAMAGE_COUNTER = Services.PLATFORM.register(BuiltInRegistries.BLOCK, DAMAGE_COUNTER_NAME,
            () -> new DamageCounterBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(1.5F)
                    .sound(SoundType.WOOD)
                    .setId(DAMAGE_COUNTER_BLOCK_KEY)));

    public static final Supplier<Item> DAMAGE_COUNTER_ITEM = Services.PLATFORM.register(BuiltInRegistries.ITEM, DAMAGE_COUNTER_NAME,
            () -> new BlockItem(DAMAGE_COUNTER.get(), new Item.Properties().useBlockDescriptionPrefix().setId(DAMAGE_COUNTER_ITEM_KEY)));

    public static final Supplier<BlockEntityType<DamageCounterBlockEntity>> DAMAGE_COUNTER_BLOCK_ENTITY = Services.PLATFORM.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE, DAMAGE_COUNTER_NAME,
            () -> new BlockEntityType<>(DamageCounterBlockEntity::new, Set.of(DAMAGE_COUNTER.get())));

    /** The mod's own creative tab with everything in it. */
    public static final Supplier<CreativeModeTab> CREATIVE_TAB = Services.PLATFORM.register(
            BuiltInRegistries.CREATIVE_MODE_TAB, DamageTester.MOD_ID,
            () -> Services.PLATFORM.createCreativeTab(
                    net.minecraft.network.chat.Component.translatable("itemGroup.damage_tester"),
                    () -> new ItemStack(DAMAGE_TESTER_ITEM.get()),
                    ModContent::creativeTabItems));

    /** The four testers, in creative-tab order. */
    public static Item[] testerItems() {
        return new Item[]{
                TESTER_ITEMS.get(TesterVariant.STANDARD).get(),
                TESTER_ITEMS.get(TesterVariant.SMALL).get(),
                TESTER_ITEMS.get(TesterVariant.HANGING).get(),
                TESTER_ITEMS.get(TesterVariant.MINECART).get()
        };
    }

    private ModContent() {
    }

    /** Forces class loading so the registrations above are queued. */
    public static void init() {
    }

    /** Variant of an entity type, or null when it is not one of ours. */
    public static TesterVariant variantOf(EntityType<?> type) {
        for (Map.Entry<TesterVariant, Supplier<EntityType<DamageTesterEntity>>> entry : TESTER_TYPES.entrySet()) {
            if (entry.getValue().get() == type) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** Items in the order they appear in the creative tab. */
    public static Item[] creativeTabItems() {
        return new Item[]{
                TESTER_ITEMS.get(TesterVariant.STANDARD).get(),
                TESTER_ITEMS.get(TesterVariant.SMALL).get(),
                TESTER_ITEMS.get(TesterVariant.HANGING).get(),
                TESTER_ITEMS.get(TesterVariant.MINECART).get(),
                DAMAGE_COUNTER_ITEM.get()
        };
    }

    /** Must run after registries are populated (common setup). Dispensers place standing testers. */
    public static void registerDispenserBehavior() {
        for (TesterVariant variant : TesterVariant.values()) {
            if (variant.hangs()) {
                continue;
            }
            DispenserBlock.registerBehavior(TESTER_ITEMS.get(variant).get(), new DefaultDispenseItemBehavior() {
                @Override
                protected ItemStack execute(BlockSource source, ItemStack stack) {
                    Direction facing = source.state().getValue(DispenserBlock.FACING);
                    ServerLevel level = source.level();
                    BlockPos target = source.pos().relative(facing);
                    if (variant.ridesMinecart()) {
                        // Like a minecart: only onto rails in front of the dispenser.
                        if (DamageTesterEntity.spawnInMinecart(level, target, stack, null, facing.toYRot()) == null) {
                            return super.execute(source, stack);
                        }
                        stack.shrink(1);
                        return stack;
                    }
                    Vec3 pos = Vec3.atBottomCenterOf(target);
                    AABB box = TESTER_TYPES.get(variant).get().getDimensions().makeBoundingBox(pos.x(), pos.y(), pos.z());
                    if (!level.noCollision(null, box) || !level.getEntities(null, box).isEmpty()) {
                        return super.execute(source, stack);
                    }
                    DamageTesterEntity tester = DamageTesterEntity.spawnFromItem(variant, level, stack, null, pos, facing.toYRot());
                    if (tester != null) {
                        stack.shrink(1);
                    }
                    return stack;
                }
            });
        }
    }
}
