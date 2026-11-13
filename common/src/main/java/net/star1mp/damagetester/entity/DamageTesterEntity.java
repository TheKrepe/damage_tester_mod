package net.star1mp.damagetester.entity;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TargetBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import net.star1mp.damagetester.ModContent;
import net.star1mp.damagetester.config.DamageTesterConfig;
import net.star1mp.damagetester.network.DamageNumberPayload;
import net.star1mp.damagetester.platform.Services;
import org.jetbrains.annotations.Nullable;

/**
 * The Damage Tester: a training dummy that never dies, measures every hit it takes
 * (after armor, enchantments and effects) and reports it back to the players.
 *
 * <p>How damage is measured: vanilla funnels all real damage through
 * {@link #actuallyHurt}, which ends with {@code setHealth(getHealth() - damage)}.
 * While inside {@code actuallyHurt} we intercept {@link #setHealth} and only record the
 * difference instead of lowering health, so the tester always stays at full health and the
 * recorded number is exactly what a normal entity would have lost (even beyond its max health).</p>
 */
public class DamageTesterEntity extends LivingEntity {
    private static final DecimalFormat ONE_DECIMAL = new DecimalFormat("0.#", DecimalFormatSymbols.getInstance(Locale.ROOT));

    /** Ticks a floating number stays visible on the client. */
    public static final int NUMBER_LIFETIME = 32;

    /** "" = default burlap, "straw", "planks:&lt;item id&gt;" or "dye:&lt;color&gt;". */
    private static final EntityDataAccessor<String> DATA_SKIN = SynchedEntityData.defineId(DamageTesterEntity.class, EntityDataSerializers.STRING);

    public static final String SKIN_STRAW = "straw";
    public static final String SKIN_JACK = "jack_o_lantern";
    public static final String SKIN_PLANKS_PREFIX = "planks:";
    public static final String SKIN_DYE_PREFIX = "dye:";


    // ---------------------------------------------------------------- server state
    private boolean measuring;
    private float measuredDamage;
    @Nullable
    private List<PendingHit> pendingHits = new ArrayList<>();
    private boolean criticalThisTick;
    private final Set<UUID> attackers = new HashSet<>();
    private long combatStartTick = -1L;
    private long lastHitTick = -1L;
    private float combatDamage;
    private int combatHits;
    private float lastHit;
    private float maxHit;
    private float lastDps;


    // ---------------------------------------------------------------- client state
    /** Which way a positive sway tilts the model; checked against screenshots. */
    private static final float SWAY_SIGN = 1.0F;
    private float wobbleAngle;
    private float wobbleVelocity;
    private float prevWobbleAngle;
    private float swayAngle;
    private float swayVelocity;
    private float prevSwayAngle;
    private final List<FloatingNumber> floatingNumbers = new ArrayList<>();
    private int numberCounter;

    @Nullable
    private TesterVariant variant;

    public DamageTesterEntity(EntityType<? extends DamageTesterEntity> type, Level level) {
        super(type, level);
        if (this.getVariant().hangs()) {
            this.setNoGravity(true);
        }
    }

    public static AttributeSupplier.Builder createAttributes() {
        return LivingEntity.createLivingAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0);
    }

    public TesterVariant getVariant() {
        if (this.variant == null) {
            TesterVariant found = ModContent.variantOf(this.getType());
            this.variant = found == null ? TesterVariant.STANDARD : found;
        }
        return this.variant;
    }

    /** Spawns a tester the way the item / dispenser do. Returns null if it could not be created. */
    @Nullable
    public static DamageTesterEntity spawnFromItem(TesterVariant variant, ServerLevel level, ItemStack stack, @Nullable Player player, Vec3 pos, float yaw) {
        DamageTesterEntity tester = ModContent.TESTER_TYPES.get(variant).get().create(level, net.minecraft.world.entity.EntitySpawnReason.SPAWN_ITEM_USE);
        if (tester == null) {
            return null;
        }
        tester.snapTo(pos.x(), pos.y(), pos.z(), yaw, 0.0F);
        tester.setYHeadRot(yaw);
        tester.setYBodyRot(yaw);
        if (stack.has(DataComponents.CUSTOM_NAME)) {
            tester.setCustomName(stack.getHoverName());
        }
        level.addFreshEntity(tester);
        SoundEvent sound = variant.hangs() ? SoundEvents.LEAD_TIED : SoundEvents.ARMOR_STAND_PLACE;
        level.playSound(null, tester.getX(), tester.getY(), tester.getZ(), sound, SoundSource.BLOCKS, 0.75F, 0.8F);
        tester.gameEvent(GameEvent.ENTITY_PLACE, player);
        return tester;
    }

    /** Backwards compatible helper for the standard tester. */
    @Nullable
    public static DamageTesterEntity spawnFromItem(ServerLevel level, ItemStack stack, @Nullable Player player, Vec3 pos, float yaw) {
        return spawnFromItem(TesterVariant.STANDARD, level, stack, player, pos, yaw);
    }


    // =================================================================== skins

    public String getSkin() {
        return this.entityData.get(DATA_SKIN);
    }

    public void setSkin(String skin) {
        this.entityData.set(DATA_SKIN, skin == null ? "" : skin);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_SKIN, "");
    }

    /** Returns the skin an item would apply, or null if the item does not change the skin. */
    @Nullable
    private static String skinFor(ItemStack stack) {
        if (stack.is(Items.HAY_BLOCK)) {
            return SKIN_STRAW;
        }
        if (stack.is(Items.JACK_O_LANTERN)) {
            return SKIN_JACK;
        }
        if (stack.is(ItemTags.PLANKS)) {
            return SKIN_PLANKS_PREFIX + BuiltInRegistries.ITEM.getKey(stack.getItem());
        }
        if (stack.getItem() instanceof net.minecraft.world.item.DyeItem dyeItem) {
            return SKIN_DYE_PREFIX + dyeItem.getDyeColor().getSerializedName();
        }
        return null;
    }

    // =================================================================== damage measuring

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        if (this.isRemoved()) {
            return false;
        }
        // /kill, the void and similar "must die" sources simply remove the tester.
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            this.dismantle(level, false);
            return true;
        }
        if (source.getEntity() instanceof Player player) {
            // Sneak + punch with an empty hand takes the tester down.
            if (player.isShiftKeyDown() && player.getMainHandItem().isEmpty() && player.getAbilities().mayBuild) {
                this.dismantle(level, !player.isCreative());
                return false;
            }
            this.attackers.add(player.getUUID());
        }
        return super.hurtServer(level, source, amount);
    }

    @Override
    protected void actuallyHurt(ServerLevel level, DamageSource source, float amount) {
        this.measuring = true;
        this.measuredDamage = 0.0F;
        try {
            super.actuallyHurt(level, source, amount);
        } finally {
            this.measuring = false;
        }
        if (this.measuredDamage > 0.0F) {
            this.queueHit(this.measuredDamage, source.typeHolder(), source);
        }
    }

    @Override
    public void setHealth(float health) {
        // pendingHits is null while the LivingEntity constructor runs.
        if (this.pendingHits == null || this.level().isClientSide()) {
            super.setHealth(health);
            return;
        }
        float lost = this.getHealth() - health;
        if (this.measuring) {
            this.measuredDamage += Math.max(0.0F, lost);
            return;
        }
        if (lost > 0.0F) {
            // Something drained health directly without going through actuallyHurt.
            this.queueHit(lost, this.damageSources().generic().typeHolder(), null);
            return;
        }
        super.setHealth(health);
    }

    @Override
    public boolean isDeadOrDying() {
        return false;
    }

    /** Called from the player mixin when a melee critical hit lands on this tester. */
    public void markCriticalHit() {
        this.criticalThisTick = true;
    }

    private void queueHit(float amount, Holder<DamageType> type, DamageSource source) {
        if (this.pendingHits != null) {
            net.minecraft.world.phys.Vec3 direction = source == null ? null : this.hitDirection(source);
            this.pendingHits.add(new PendingHit(amount, type,
                    direction == null ? 0.0F : (float) direction.x, direction == null ? 0.0F : (float) direction.z));
        }
    }

    /**
     * The horizontal direction the hit travels in (from the attacker towards the tester), so the
     * tester can lean away from it. Null when the damage has no position (fire, poison, ...).
     */
    private net.minecraft.world.phys.Vec3 hitDirection(DamageSource source) {
        net.minecraft.world.phys.Vec3 direction = null;
        Entity direct = source.getDirectEntity();
        if (direct != null && direct != this) {
            net.minecraft.world.phys.Vec3 motion = direct.getDeltaMovement();
            if (direct instanceof net.minecraft.world.entity.projectile.Projectile && motion.horizontalDistanceSqr() > 1.0E-4) {
                direction = motion;
            } else {
                direction = this.position().subtract(direct.position());
            }
        } else if (source.getSourcePosition() != null) {
            direction = this.position().subtract(source.getSourcePosition());
        }
        if (direction == null) {
            return null;
        }
        direction = new net.minecraft.world.phys.Vec3(direction.x, 0.0, direction.z);
        return direction.lengthSqr() < 1.0E-6 ? null : direction.normalize();
    }

    /**
     * Hits are flushed once per tick instead of immediately, because vanilla only reports a
     * critical hit after the damage has already been applied.
     */
    private void flushHits(ServerLevel level) {
        if (this.pendingHits == null || this.pendingHits.isEmpty()) {
            this.criticalThisTick = false;
            return;
        }
        DamageTesterConfig.Values config = DamageTesterConfig.get();
        long now = level.getGameTime();
        long resetTicks = config.combatResetSeconds * 20L;
        if (this.lastHitTick < 0 || now - this.lastHitTick > resetTicks) {
            this.combatStartTick = now;
            this.combatDamage = 0.0F;
            this.combatHits = 0;
            this.maxHit = 0.0F;
        }
        this.lastHitTick = now;

        float tickTotal = 0.0F;
        boolean critical = this.criticalThisTick;
        for (int i = 0; i < this.pendingHits.size(); i++) {
            PendingHit hit = this.pendingHits.get(i);
            tickTotal += hit.amount();
            this.combatDamage += hit.amount();
            this.combatHits++;
            this.maxHit = Math.max(this.maxHit, hit.amount());
            // Only the first hit of a tick can be the melee crit.
            Services.PLATFORM.sendToTrackingPlayers(this,
                    new DamageNumberPayload(this.getId(), hit.amount(), hit.type(), critical && i == 0, hit.dirX(), hit.dirZ()));
        }
        this.pendingHits.clear();
        this.criticalThisTick = false;
        this.lastHit = tickTotal;
        float seconds = Math.max(1.0F, (now - this.combatStartTick + 1) / 20.0F);
        this.lastDps = this.combatDamage / seconds;

        if (config.hayParticles) {
            int count = Mth.clamp(2 + (int) (tickTotal / 2.0F), 2, 16);
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.HAY_BLOCK.defaultBlockState()),
                    this.getX(), this.getY(0.6), this.getZ(), count, 0.2, 0.3, 0.2, 0.05);
        }
        this.powerTargetBlock(level, tickTotal);
        if (config.showDpsMessage) {
            this.sendDpsMessage(level);
        }
    }

    /**
     * A tester standing on (or hanging from) a target block makes it emit redstone:
     * 1 damage = signal strength 1, 15 damage or more = 15. The block resets itself a second later.
     */
    private void powerTargetBlock(ServerLevel level, float damage) {
        BlockPos pos = this.getVariant().hangs()
                ? BlockPos.containing(this.getX(), this.getY() + this.getBbHeight() + TesterVariant.ROPE_LENGTH + 0.05, this.getZ())
                : BlockPos.containing(this.getX(), this.getY() - 0.05, this.getZ());
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof TargetBlock) || !state.hasProperty(BlockStateProperties.POWER)) {
            return;
        }
        int power = Mth.clamp(Mth.ceil(damage), 1, 15);
        if (power > state.getValue(BlockStateProperties.POWER)) {
            level.setBlock(pos, state.setValue(BlockStateProperties.POWER, power), 3);
        }
        level.scheduleTick(pos, state.getBlock(), 20);
    }

    private void sendDpsMessage(ServerLevel level) {
        Component message = Component.translatable("message.damage_tester.stats",
                ONE_DECIMAL.format(this.lastHit),
                ONE_DECIMAL.format(this.lastDps),
                ONE_DECIMAL.format(this.combatDamage),
                this.combatHits);
        for (UUID uuid : this.attackers) {
            if (level.getPlayerByUUID(uuid) instanceof ServerPlayer player && player.distanceToSqr(this) < 64.0 * 64.0) {
                player.displayClientMessage(message, true);
            }
        }
    }

    /** Server-side statistics of the current (or last) series of hits, shown by the damage counter block. */
    public Stats getStats() {
        boolean active = this.lastHitTick >= 0;
        return new Stats(this.combatHits > 0, active, this.lastHit, this.lastDps, this.combatDamage, this.combatHits, this.maxHit);
    }

    public record Stats(boolean hasData, boolean active, float lastHit, float dps, float total, int hits, float maxHit) {
        public static final Stats EMPTY = new Stats(false, false, 0, 0, 0, 0, 0);
    }

    /** What dismantling drops instead of the pick result (used when a minecart tester loses its cart). */
    @Nullable
    private ItemStack dropOnDismantle;
    private boolean allowDismount;

    private void dismantle(ServerLevel level, boolean drop) {
        if (this.isRemoved()) {
            return;
        }
        if (drop) {
            this.spawnAtLocation(level, this.dropOnDismantle != null ? this.dropOnDismantle : this.getPickResult());
        }
        // A minecart tester takes its cart with it when it is picked up.
        if (this.getVariant().ridesMinecart() && this.getVehicle() instanceof AbstractMinecart cart && !cart.isRemoved()
                && this.dropOnDismantle == null) {
            cart.discard();
        }
        this.allowDismount = true;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = this.getItemBySlot(slot);
            if (!stack.isEmpty()) {
                this.spawnAtLocation(level, stack.copy());
                this.setItemSlot(slot, ItemStack.EMPTY);
            }
        }
        this.playSound(SoundEvents.ARMOR_STAND_BREAK, 1.0F, 1.0F);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.HAY_BLOCK.defaultBlockState()),
                this.getX(), this.getY(0.66), this.getZ(), 20, 0.2, 0.4, 0.2, 0.05);
        this.gameEvent(GameEvent.ENTITY_DIE);
        this.discard();
    }

    /**
     * Minecart testers stay in their cart, even on an activator rail. They only leave it when the
     * cart is gone or the tester itself is removed.
     */
    @Override
    public void stopRiding() {
        if (this.getVariant().ridesMinecart() && !this.allowDismount && !this.isRemoved()
                && this.getVehicle() != null && !this.getVehicle().isRemoved()) {
            return;
        }
        super.stopRiding();
    }

    /** Puts a minecart on the rail at {@code railPos} with a tester inside it. */
    @Nullable
    public static DamageTesterEntity spawnInMinecart(ServerLevel level, BlockPos railPos, ItemStack stack, @Nullable Player player, float yaw) {
        BlockState rail = level.getBlockState(railPos);
        if (!BaseRailBlock.isRail(rail)) {
            return null;
        }
        double slope = rail.getBlock() instanceof BaseRailBlock railBlock && rail.getValue(railBlock.getShapeProperty()).isSlope() ? 0.5 : 0.0;
        AbstractMinecart cart = AbstractMinecart.createMinecart(level, railPos.getX() + 0.5, railPos.getY() + 0.0625 + slope,
                railPos.getZ() + 0.5, EntityType.MINECART, net.minecraft.world.entity.EntitySpawnReason.DISPENSER, ItemStack.EMPTY, player);
        if (cart == null) {
            return null;
        }
        level.addFreshEntity(cart);
        DamageTesterEntity tester = spawnFromItem(TesterVariant.MINECART, level, stack, player, cart.position(), yaw);
        if (tester == null) {
            cart.discard();
            return null;
        }
        tester.startRiding(cart, true, true);
        return tester;
    }

    @Override
    public void kill(ServerLevel level) {
        this.dismantle(level, false);
    }

    // =================================================================== ticking

    @Override
    public void tick() {
        super.tick();
        // The tester never turns by itself.
        this.setYBodyRot(this.getYRot());
        this.setYHeadRot(this.getYRot());
        this.hurtTime = 0;

        if (this.level() instanceof ServerLevel serverLevel) {
            this.flushHits(serverLevel);
            if (this.lastHitTick >= 0 && serverLevel.getGameTime() - this.lastHitTick > DamageTesterConfig.get().combatResetSeconds * 20L) {
                this.lastHitTick = -1L;
                this.attackers.clear();
            }
            if (this.getVariant().hangs() && this.tickCount % 10 == 0 && !this.hasSupportAbove()) {
                this.dismantle(serverLevel, true);
                return;
            }
            if (this.getVariant().ridesMinecart() && this.tickCount > 5) {
                Entity vehicle = this.getVehicle();
                if (vehicle == null || vehicle.isRemoved()) {
                    // The minecart was broken (it drops itself): what's left is a normal tester.
                    this.dropOnDismantle = new ItemStack(ModContent.DAMAGE_TESTER_ITEM.get());
                    this.dismantle(serverLevel, true);
                    return;
                }
            }
        } else {
            this.clientTick();
        }
    }

    private boolean hasSupportAbove() {
        BlockPos above = BlockPos.containing(this.getX(), this.getY() + this.getBbHeight() + TesterVariant.ROPE_LENGTH + 0.05, this.getZ());
        BlockState state = this.level().getBlockState(above);
        return !state.isAir() && state.isFaceSturdy(this.level(), above, Direction.DOWN);
    }


    @Override
    public void travel(Vec3 input) {
        TesterVariant v = this.getVariant();
        if (v.hangs()) {
            return; // fixed in place
        }
        super.travel(input);
    }

    @Override
    protected void tickHeadTurn(float yBodyRotDelta) {
        // no head turning, it's a sack of hay
    }

    // =================================================================== interaction

    @Override
    public InteractionResult interactAt(Player player, Vec3 location, InteractionHand hand) {
        if (player.isSpectator() || !player.getAbilities().mayBuild) {
            return InteractionResult.PASS;
        }
        ItemStack held = player.getItemInHand(hand);
        if (held.isEmpty()) {
            if (hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }
            EquipmentSlot slot = this.getClickedSlot(location);
            if (slot == null) {
                return InteractionResult.PASS;
            }
            if (!this.level().isClientSide()) {
                player.setItemInHand(hand, this.getItemBySlot(slot).copy());
                this.setItemSlot(slot, ItemStack.EMPTY);
            }
            return InteractionResult.SUCCESS;
        }

        // Shears take any skin off again.
        if (held.is(Items.SHEARS) && !this.getSkin().isEmpty()) {
            if (!this.level().isClientSide()) {
                this.setSkin("");
                this.playSound(SoundEvents.SHEEP_SHEAR, 1.0F, 1.0F);
            }
            return InteractionResult.SUCCESS;
        }

        String skin = skinFor(held);
        if (skin != null) {
            if (skin.equals(this.getSkin())) {
                return InteractionResult.PASS;
            }
            if (!this.level().isClientSide()) {
                this.setSkin(skin);
                SoundEvent sound = skin.startsWith(SKIN_DYE_PREFIX) ? SoundEvents.DYE_USE
                        : skin.equals(SKIN_STRAW) ? SoundEvents.GRASS_PLACE
                        : skin.equals(SKIN_JACK) ? SoundEvents.PUMPKIN_CARVE : SoundEvents.WOOD_PLACE;
                this.playSound(sound, 1.0F, 1.0F);
                if (!player.isCreative()) {
                    held.shrink(1);
                }
            }
            return InteractionResult.SUCCESS;
        }

        EquipmentSlot slot = this.getEquipmentSlotForItem(held);
        if (slot.getType() != EquipmentSlot.Type.HUMANOID_ARMOR) {
            return InteractionResult.PASS;
        }
        if (!this.level().isClientSide()) {
            ItemStack previous = this.getItemBySlot(slot).copy();
            ItemStack toEquip = player.isCreative() ? held.copyWithCount(1) : held.split(1);
            this.setItemSlot(slot, toEquip);
            if (!previous.isEmpty()) {
                if (player.getItemInHand(hand).isEmpty()) {
                    player.setItemInHand(hand, previous);
                } else if (!player.getInventory().add(previous) && this.level() instanceof ServerLevel serverLevel) {
                    this.spawnAtLocation(serverLevel, previous);
                }
            }
        }
        return InteractionResult.SUCCESS;
    }

    /** Which armor slot the player aimed at, or the first non-empty slot from the top. */
    @Nullable
    private EquipmentSlot getClickedSlot(Vec3 location) {
        double y = location.y / this.getBbHeight() * 1.9;
        EquipmentSlot aimed;
        if (y >= 1.45) {
            aimed = EquipmentSlot.HEAD;
        } else if (y >= 0.95) {
            aimed = EquipmentSlot.CHEST;
        } else if (y >= 0.45) {
            aimed = EquipmentSlot.LEGS;
        } else {
            aimed = EquipmentSlot.FEET;
        }
        if (this.hasItemInSlot(aimed)) {
            return aimed;
        }
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            if (this.hasItemInSlot(slot)) {
                return slot;
            }
        }
        return null;
    }

    @Override
    public ItemStack getPickResult() {
        ItemStack stack = new ItemStack(ModContent.TESTER_ITEMS.get(this.getVariant()).get());
        if (this.hasCustomName()) {
            stack.set(DataComponents.CUSTOM_NAME, this.getCustomName());
        }
        return stack;
    }

    // =================================================================== physics: it just stands there

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void pushEntities() {
    }

    @Override
    public void push(double x, double y, double z) {
    }

    @Override
    public boolean isPushedByFluid() {
        return false;
    }

    @Override
    public boolean causeFallDamage(double fallDistance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    public HumanoidArm getMainArm() {
        return HumanoidArm.RIGHT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.WOOL_HIT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.ARMOR_STAND_BREAK;
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putString("Skin", this.getSkin());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        this.setSkin(input.getStringOr("Skin", ""));
        if (this.getVariant().hangs()) {
            this.setNoGravity(true);
        }
    }

    // =================================================================== client side animation

    /** Called on the client when the server reports a hit. */
    public void onClientHit(float amount, int color, boolean critical, float dirX, float dirZ) {
        float intensity = DamageTesterConfig.get().wobbleIntensity;
        if (intensity > 0.0F) {
            // Bigger hits push harder, but a one-shot of 1000 damage should not spin it around.
            float push = (float) Math.sqrt(Math.min(amount, 100.0F)) * 0.09F * intensity;
            if (this.getVariant().hangs()) {
                push *= 1.4F; // a punching bag swings further
            }
            // Lean the way the hit travels: away from the attacker. Hits without a direction
            // (fire, poison, ...) push it back as if they came from the front.
            float back = 1.0F;
            float left = 0.0F;
            if (dirX * dirX + dirZ * dirZ > 1.0E-4F) {
                float yaw = this.yBodyRot * Mth.DEG_TO_RAD;
                float forwardX = -Mth.sin(yaw);
                float forwardZ = Mth.cos(yaw);
                back = -(dirX * forwardX + dirZ * forwardZ);
                left = dirX * forwardZ - dirZ * forwardX;
            }
            this.wobbleVelocity += push * back;
            float jitter = (this.random.nextFloat() - 0.5F) * 0.3F;
            this.swayVelocity += push * (left * SWAY_SIGN * 0.8F + jitter);
        }
        if (DamageTesterConfig.get().showDamageNumbers) {
            int slot = this.numberCounter++;
            this.floatingNumbers.add(new FloatingNumber(amount, color, critical, this.tickCount, slot));
            if (this.floatingNumbers.size() > 24) {
                this.floatingNumbers.remove(0);
            }
        }
    }

    private void clientTick() {
        this.prevWobbleAngle = this.wobbleAngle;
        this.prevSwayAngle = this.swayAngle;
        // Damped springs: the tester leans back and bounces upright again.
        boolean hangs = this.getVariant().hangs();
        this.wobbleVelocity += -this.wobbleAngle * (hangs ? 0.12F : 0.22F);
        this.wobbleVelocity *= hangs ? 0.93F : 0.86F;
        this.wobbleAngle = Mth.clamp(this.wobbleAngle + this.wobbleVelocity, -0.7F, 0.7F);
        this.swayVelocity += -this.swayAngle * 0.18F;
        this.swayVelocity *= 0.88F;
        this.swayAngle = Mth.clamp(this.swayAngle + this.swayVelocity, -0.35F, 0.35F);
        if (Math.abs(this.wobbleAngle) < 1.0E-4F && Math.abs(this.wobbleVelocity) < 1.0E-4F) {
            this.wobbleAngle = 0.0F;
            this.wobbleVelocity = 0.0F;
        }
        Iterator<FloatingNumber> it = this.floatingNumbers.iterator();
        while (it.hasNext()) {
            if (this.tickCount - it.next().spawnTick() > NUMBER_LIFETIME) {
                it.remove();
            }
        }
    }

    public float getWobble(float partialTick) {
        return Mth.lerp(partialTick, this.prevWobbleAngle, this.wobbleAngle);
    }

    public float getSway(float partialTick) {
        return Mth.lerp(partialTick, this.prevSwayAngle, this.swayAngle);
    }

    public List<FloatingNumber> getFloatingNumbers() {
        return this.floatingNumbers;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 64.0 * 64.0;
    }

    private record PendingHit(float amount, Holder<DamageType> type, float dirX, float dirZ) {
    }

    /** A number popping off the tester on the client. */
    public record FloatingNumber(float amount, int color, boolean critical, int spawnTick, int slot) {
    }
}
