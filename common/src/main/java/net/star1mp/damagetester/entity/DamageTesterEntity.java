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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
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

    // moving tester
    public static final int MOVE_CIRCLE = 0;
    public static final int MOVE_LINE = 1;
    public static final int MOVE_STOPPED = 2;
    private static final float MOVE_SPEED = 0.11F;
    private static final float CIRCLE_RADIUS = 2.5F;
    private static final float LINE_HALF_LENGTH = 3.0F;

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

    private double anchorX;
    private double anchorZ;
    private float travelAngle;
    private int moveMode = MOVE_CIRCLE;
    private float moveDirection = 1.0F;
    private boolean hasAnchor;

    // ---------------------------------------------------------------- client state
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
        tester.setAnchor(pos, yaw);
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

    private void setAnchor(Vec3 pos, float yaw) {
        this.hasAnchor = true;
        if (this.getVariant() == TesterVariant.MOVING) {
            // The circle starts at the placement spot: put its centre to the tester's right.
            float rad = yaw * Mth.DEG_TO_RAD;
            this.anchorX = pos.x() - Mth.cos(rad) * CIRCLE_RADIUS;
            this.anchorZ = pos.z() - Mth.sin(rad) * CIRCLE_RADIUS;
            this.travelAngle = (float) Math.atan2(pos.z() - this.anchorZ, pos.x() - this.anchorX);
        } else {
            this.anchorX = pos.x();
            this.anchorZ = pos.z();
        }
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
        DyeColor dye = stack.get(DataComponents.DYE);
        if (dye != null) {
            return SKIN_DYE_PREFIX + dye.getSerializedName();
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
            this.queueHit(this.measuredDamage, source.typeHolder());
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
            this.queueHit(lost, this.damageSources().generic().typeHolder());
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

    private void queueHit(float amount, Holder<DamageType> type) {
        if (this.pendingHits != null) {
            this.pendingHits.add(new PendingHit(amount, type));
        }
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
                    new DamageNumberPayload(this.getId(), hit.amount(), hit.type(), critical && i == 0));
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
                player.sendOverlayMessage(message);
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

    private void dismantle(ServerLevel level, boolean drop) {
        if (this.isRemoved()) {
            return;
        }
        if (drop) {
            this.spawnAtLocation(level, this.getPickResult());
        }
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

    @Override
    public void kill(ServerLevel level) {
        this.dismantle(level, false);
    }

    // =================================================================== ticking

    @Override
    public void tick() {
        super.tick();
        if (this.getVariant() != TesterVariant.MOVING) {
            // The tester never turns by itself.
            this.setYBodyRot(this.getYRot());
            this.setYHeadRot(this.getYRot());
        }
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
            if (this.getVariant() == TesterVariant.MOVING) {
                this.serverMove();
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

    /** Rolls the moving tester along a circle or back and forth on a line around its anchor. */
    private void serverMove() {
        if (!this.hasAnchor) {
            this.setAnchor(this.position(), this.getYRot());
        }
        double vy = this.isNoGravity() ? 0.0 : (this.onGround() ? -0.04 : Math.max(this.getDeltaMovement().y - 0.08, -1.0));
        double dx = 0.0;
        double dz = 0.0;
        if (this.moveMode != MOVE_STOPPED) {
            double targetX;
            double targetZ;
            if (this.moveMode == MOVE_CIRCLE) {
                this.travelAngle += this.moveDirection * MOVE_SPEED / CIRCLE_RADIUS;
                targetX = this.anchorX + Math.cos(this.travelAngle) * CIRCLE_RADIUS;
                targetZ = this.anchorZ + Math.sin(this.travelAngle) * CIRCLE_RADIUS;
            } else {
                // travelAngle is used as the position along the line, bouncing between both ends.
                this.travelAngle += this.moveDirection * MOVE_SPEED;
                if (Math.abs(this.travelAngle) > LINE_HALF_LENGTH) {
                    this.travelAngle = Math.copySign(LINE_HALF_LENGTH, this.travelAngle);
                    this.moveDirection = -this.moveDirection;
                }
                // The line runs sideways relative to the way the tester was placed.
                float rad = this.lineYaw * Mth.DEG_TO_RAD;
                targetX = this.anchorX + Math.cos(rad) * this.travelAngle;
                targetZ = this.anchorZ + Math.sin(rad) * this.travelAngle;
            }
            dx = Mth.clamp(targetX - this.getX(), -0.3, 0.3);
            dz = Mth.clamp(targetZ - this.getZ(), -0.3, 0.3);
        }
        this.move(MoverType.SELF, new Vec3(dx, vy, dz));
        this.setDeltaMovement(0.0, vy, 0.0);
        if (this.horizontalCollision && this.moveMode != MOVE_STOPPED) {
            this.moveDirection = -this.moveDirection;
        }
        if (dx * dx + dz * dz > 1.0E-4) {
            float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
            this.setYRot(yaw);
            this.setYBodyRot(yaw);
            this.setYHeadRot(yaw);
        }
    }

    private float lineYaw;

    @Override
    public void travel(Vec3 input) {
        TesterVariant v = this.getVariant();
        if (v == TesterVariant.MOVING || v.hangs()) {
            return; // moved manually / fixed in place
        }
        super.travel(input);
    }

    @Override
    protected void tickHeadTurn(float yBodyRotDelta) {
        // no head turning, it's a sack of hay
    }

    // =================================================================== interaction

    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
        if (player.isSpectator() || !player.getAbilities().mayBuild) {
            return InteractionResult.PASS;
        }
        ItemStack held = player.getItemInHand(hand);
        if (held.isEmpty()) {
            if (hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }
            if (player.isShiftKeyDown() && this.getVariant() == TesterVariant.MOVING) {
                if (!this.level().isClientSide()) {
                    this.moveMode = (this.moveMode + 1) % 3;
                    if (this.moveMode == MOVE_LINE) {
                        this.lineYaw = this.getYRot();
                        this.anchorX = this.getX();
                        this.anchorZ = this.getZ();
                        this.travelAngle = 0.0F;
                    } else if (this.moveMode == MOVE_CIRCLE) {
                        this.setAnchor(this.position(), this.getYRot());
                    }
                    player.sendOverlayMessage(Component.translatable("message.damage_tester.move_mode." + this.moveMode));
                    this.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.6F, 1.0F);
                }
                return InteractionResult.SUCCESS;
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
        if (this.hasAnchor) {
            output.putBoolean("HasAnchor", true);
            output.putDouble("AnchorX", this.anchorX);
            output.putDouble("AnchorZ", this.anchorZ);
            output.putFloat("TravelAngle", this.travelAngle);
            output.putFloat("MoveDirection", this.moveDirection);
            output.putInt("MoveMode", this.moveMode);
            output.putFloat("LineYaw", this.lineYaw);
        }
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        this.setSkin(input.getStringOr("Skin", ""));
        this.hasAnchor = input.getBooleanOr("HasAnchor", false);
        if (this.hasAnchor) {
            this.anchorX = input.getDoubleOr("AnchorX", this.getX());
            this.anchorZ = input.getDoubleOr("AnchorZ", this.getZ());
            this.travelAngle = input.getFloatOr("TravelAngle", 0.0F);
            this.moveDirection = input.getFloatOr("MoveDirection", 1.0F);
            this.moveMode = Mth.clamp(input.getIntOr("MoveMode", MOVE_CIRCLE), 0, 2);
            this.lineYaw = input.getFloatOr("LineYaw", this.getYRot());
        }
        if (this.getVariant().hangs()) {
            this.setNoGravity(true);
        }
    }

    // =================================================================== client side animation

    /** Called on the client when the server reports a hit. */
    public void onClientHit(float amount, int color, boolean critical) {
        float intensity = DamageTesterConfig.get().wobbleIntensity;
        if (intensity > 0.0F) {
            // Bigger hits push harder, but a one-shot of 1000 damage should not spin it around.
            float push = (float) Math.sqrt(Math.min(amount, 100.0F)) * 0.09F * intensity;
            if (this.getVariant().hangs()) {
                push *= 1.4F; // a punching bag swings further
            }
            this.wobbleVelocity -= push;
            float side = (this.random.nextBoolean() ? 1.0F : -1.0F);
            this.swayVelocity += side * push * 0.35F;
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

    private record PendingHit(float amount, Holder<DamageType> type) {
    }

    /** A number popping off the tester on the client. */
    public record FloatingNumber(float amount, int color, boolean critical, int spawnTick, int slot) {
    }
}
