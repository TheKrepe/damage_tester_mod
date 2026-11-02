package net.star1mp.damagetester.block;

import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.star1mp.damagetester.ModContent;
import net.star1mp.damagetester.entity.DamageTesterEntity;

/** Polls the nearest damage tester twice a second and syncs its statistics to clients. */
public class DamageCounterBlockEntity extends BlockEntity {
    public static final double RANGE = 8.0;

    private DamageTesterEntity.Stats stats = DamageTesterEntity.Stats.EMPTY;
    private boolean hasTester;

    public DamageCounterBlockEntity(BlockPos pos, BlockState state) {
        super(ModContent.DAMAGE_COUNTER_BLOCK_ENTITY.get(), pos, state);
    }

    public DamageTesterEntity.Stats getStats() {
        return this.stats;
    }

    public boolean hasTester() {
        return this.hasTester;
    }

    void serverTick() {
        if (this.level == null || this.level.getGameTime() % 10 != 0) {
            return;
        }
        Vec3 center = Vec3.atCenterOf(this.getBlockPos());
        List<DamageTesterEntity> testers = this.level.getEntitiesOfClass(DamageTesterEntity.class, new AABB(this.getBlockPos()).inflate(RANGE),
                tester -> tester.isAlive() && tester.distanceToSqr(center) <= RANGE * RANGE);
        DamageTesterEntity nearest = testers.stream()
                .min(Comparator.comparingDouble(tester -> tester.distanceToSqr(center)))
                .orElse(null);
        boolean found = nearest != null;
        DamageTesterEntity.Stats newStats = found ? nearest.getStats() : DamageTesterEntity.Stats.EMPTY;
        if (found != this.hasTester || !newStats.equals(this.stats)) {
            this.hasTester = found;
            this.stats = newStats;
            this.setChanged();
            BlockState state = this.getBlockState();
            this.level.sendBlockUpdated(this.getBlockPos(), state, state, 3);
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putBoolean("HasTester", this.hasTester);
        output.putBoolean("HasData", this.stats.hasData());
        output.putBoolean("Active", this.stats.active());
        output.putFloat("LastHit", this.stats.lastHit());
        output.putFloat("Dps", this.stats.dps());
        output.putFloat("Total", this.stats.total());
        output.putInt("Hits", this.stats.hits());
        output.putFloat("MaxHit", this.stats.maxHit());
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.hasTester = input.getBooleanOr("HasTester", false);
        this.stats = new DamageTesterEntity.Stats(
                input.getBooleanOr("HasData", false),
                input.getBooleanOr("Active", false),
                input.getFloatOr("LastHit", 0.0F),
                input.getFloatOr("Dps", 0.0F),
                input.getFloatOr("Total", 0.0F),
                input.getIntOr("Hits", 0),
                input.getFloatOr("MaxHit", 0.0F));
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return this.saveCustomOnly(registries);
    }
}
