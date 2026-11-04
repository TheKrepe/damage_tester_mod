package net.star1mp.damagetester.fabric.test.trailer;

import com.mojang.datafixers.util.Pair;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.star1mp.damagetester.ModContent;
import net.star1mp.damagetester.block.DamageCounterBlock;

/**
 * Builds the trailer set: a small survival homestead (cabin, farm, training pen, redstone strip),
 * an arena for the multiplayer scenes, a minecart loop and a skin showroom. Everything is placed
 * relative to an origin found on flat plains.
 */
public final class TrailerWorld {
    public int cx;
    public int cz;
    public int g; // y of the first air block above the ground

    public Vec3 v(double x, double y, double z) {
        return new Vec3(this.cx + x, this.g + y, this.cz + z);
    }

    public BlockPos p(int x, int y, int z) {
        return new BlockPos(this.cx + x, this.g + y, this.cz + z);
    }

    // ------------------------------------------------------------------ site selection

    public void findSite(ServerLevel level, BlockPos start) {
        BlockPos best = start;
        double bestScore = Double.MAX_VALUE;
        int[][] offsets = {{0, 0}, {900, 0}, {0, 900}, {-900, 0}, {0, -900}, {900, 900}};
        for (int[] o : offsets) {
            Pair<BlockPos, Holder<Biome>> found = level.findClosestBiome3d(
                    h -> h.is(Biomes.PLAINS) || h.is(Biomes.SUNFLOWER_PLAINS),
                    start.offset(o[0], 0, o[1]), 1600, 32, 64);
            if (found == null) {
                continue;
            }
            BlockPos c = found.getFirst();
            double score = this.roughness(level, c.getX(), c.getZ());
            System.out.println("[trailer] candidate " + c + " score " + score);
            if (score < bestScore) {
                bestScore = score;
                best = c;
            }
            if (score < 1.5) {
                break;
            }
        }
        this.cx = best.getX();
        this.cz = best.getZ();
        this.g = this.surface(level, this.cx, this.cz) + 1;
        System.out.println("[trailer] site " + this.cx + " " + this.g + " " + this.cz);
    }

    private double roughness(ServerLevel level, int x0, int z0) {
        double sum = 0;
        double sum2 = 0;
        int n = 0;
        int water = 0;
        for (int x = -48; x <= 48; x += 8) {
            for (int z = -24; z <= 56; z += 8) {
                int h = this.surface(level, x0 + x, z0 + z);
                BlockPos top = new BlockPos(x0 + x, h, z0 + z);
                if (level.getFluidState(top).is(FluidTags.WATER) || level.getFluidState(top.above()).is(FluidTags.WATER)) {
                    water++;
                }
                sum += h;
                sum2 += h * h;
                n++;
            }
        }
        double mean = sum / n;
        return Math.sqrt(Math.max(0, sum2 / n - mean * mean)) + water * 2.0;
    }

    /** Y of the top solid (non-leaf, non-plant) block. */
    private int surface(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        while (y > level.getMinY()) {
            BlockState s = level.getBlockState(new BlockPos(x, y, z));
            if (s.isSolid() && !s.is(Blocks.OAK_LOG) && !s.is(Blocks.BIRCH_LOG)) {
                break;
            }
            if (!s.getFluidState().isEmpty()) {
                break;
            }
            y--;
        }
        return y;
    }

    // ------------------------------------------------------------------ terraforming

    public void flatten(ServerLevel level) {
        int ground = this.g - 1;
        for (int x = -64; x <= 64; x++) {
            for (int z = -40; z <= 72; z++) {
                double dx = Math.max(0, Math.max(-48 - x, x - 48));
                double dz = Math.max(0, Math.max(-24 - z, z - 56));
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > 16) {
                    continue;
                }
                double w = d <= 0 ? 1.0 : 0.5 + 0.5 * Math.cos(Math.PI * d / 16.0);
                int wx = this.cx + x;
                int wz = this.cz + z;
                int h = this.surface(level, wx, wz);
                int target = (int) Math.round(h + (ground - h) * w);
                boolean core = d <= 0;
                // clear everything above the new surface (trees too, inside the core)
                int top = core ? Math.max(h, target) + 32 : Math.max(h, target) + 2;
                for (int y = target + 1; y <= top; y++) {
                    BlockPos pos = new BlockPos(wx, y, wz);
                    BlockState s = level.getBlockState(pos);
                    if (!s.isAir() && (core || y <= h + 1 || !s.is(net.minecraft.tags.BlockTags.LEAVES) && !s.is(net.minecraft.tags.BlockTags.LOGS))) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2 | 16);
                    }
                }
                for (int y = Math.min(h, target) - 3; y < target; y++) {
                    level.setBlock(new BlockPos(wx, y, wz), Blocks.DIRT.defaultBlockState(), 2 | 16);
                }
                level.setBlock(new BlockPos(wx, target, wz), Blocks.GRASS_BLOCK.defaultBlockState(), 2 | 16);
            }
        }
    }

    public void decorate(ServerLevel level) {
        Random rnd = new Random(42);
        for (int x = -60; x <= 60; x++) {
            for (int z = -36; z <= 68; z++) {
                BlockPos pos = new BlockPos(this.cx + x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, this.cx + x, this.cz + z), this.cz + z);
                if (!level.getBlockState(pos.below()).is(Blocks.GRASS_BLOCK) || !level.getBlockState(pos).isAir()) {
                    continue;
                }
                double r = rnd.nextDouble();
                Block b = r < 0.16 ? Blocks.SHORT_GRASS
                        : r < 0.175 ? Blocks.POPPY
                        : r < 0.19 ? Blocks.DANDELION
                        : r < 0.20 ? Blocks.OXEYE_DAISY
                        : r < 0.21 ? Blocks.AZURE_BLUET
                        : r < 0.215 ? Blocks.CORNFLOWER : null;
                if (b != null) {
                    level.setBlock(pos, b.defaultBlockState(), 2);
                } else if (r < 0.222) {
                    level.setBlock(pos, Blocks.TALL_GRASS.defaultBlockState(), 2);
                    level.setBlock(pos.above(), Blocks.TALL_GRASS.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), 2);
                }
            }
        }
    }

    /** Positions (relative) for decorative trees, placed with /place feature by the caller. */
    public static final int[][] TREES = {
            {-30, -16}, {-22, -22}, {-40, -6}, {18, -18}, {30, -12}, {44, -4}, {52, 14}, {-52, 12}, {-56, 30},
            {54, 40}, {50, 58}, {-50, 52}, {-24, 60}, {6, 62}, {28, 62}, {-8, -26}, {40, 16}, {-18, 18}, {16, 20}
    };

    // ------------------------------------------------------------------ sets

    public void buildAll(ServerLevel level) {
        this.cabin(level);
        this.farm(level);
        this.pen(level);
        this.redstone(level);
        this.arena(level);
        this.cartLoop(level);
        this.showroom(level);
    }

    private void set(ServerLevel level, int x, int y, int z, BlockState s) {
        level.setBlock(this.p(x, y, z), s, 3);
    }

    private void set(ServerLevel level, int x, int y, int z, Block b) {
        this.set(level, x, y, z, b.defaultBlockState());
    }

    private void clearPlants(ServerLevel level, int x0, int z0, int x1, int z1) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = 0; y < 3; y++) {
                    BlockState s = level.getBlockState(this.p(x, y, z));
                    if (!s.isAir() && !s.isSolid()) {
                        level.setBlock(this.p(x, y, z), Blocks.AIR.defaultBlockState(), 2 | 16);
                    }
                }
            }
        }
    }

    private void cabin(ServerLevel level) {
        int x0 = -14;
        int x1 = -8;
        int z0 = -9;
        int z1 = -3;
        this.clearPlants(level, x0 - 2, z0 - 2, x1 + 2, z1 + 2);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                this.set(level, x, -1, z, Blocks.COBBLESTONE);
                boolean edgeX = x == x0 || x == x1;
                boolean edgeZ = z == z0 || z == z1;
                for (int y = 0; y < 4; y++) {
                    BlockState s;
                    if (edgeX && edgeZ) {
                        s = Blocks.OAK_LOG.defaultBlockState();
                    } else if (edgeX || edgeZ) {
                        boolean window = (y == 1 || y == 2) && ((edgeZ && (x == x0 + 2 || x == x1 - 2)) || (edgeX && z == (z0 + z1) / 2));
                        s = window ? Blocks.GLASS_PANE.defaultBlockState() : Blocks.SPRUCE_PLANKS.defaultBlockState();
                    } else {
                        s = Blocks.AIR.defaultBlockState();
                    }
                    this.set(level, x, y, z, s);
                }
            }
        }
        // door in the south wall
        int dx = (x0 + x1) / 2;
        BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH);
        this.set(level, dx, 0, z1, Blocks.AIR);
        this.set(level, dx, 1, z1, Blocks.AIR);
        level.setBlock(this.p(dx, 0, z1), door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER), 2 | 16);
        level.setBlock(this.p(dx, 1, z1), door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), 2 | 16);
        this.set(level, dx - 1, 2, z1 + 1, Blocks.LANTERN);
        this.set(level, dx - 1, 1, z1 + 1, Blocks.OAK_FENCE);
        this.set(level, dx - 1, 0, z1 + 1, Blocks.OAK_FENCE);
        // gable roof running east-west
        int zc = (z0 + z1) / 2;
        for (int k = 0; k <= 3; k++) {
            for (int x = x0 - 1; x <= x1 + 1; x++) {
                this.set(level, x, 4 + k, z0 - 1 + k, Blocks.DARK_OAK_STAIRS.defaultBlockState()
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH).setValue(BlockStateProperties.HALF, Half.BOTTOM));
                this.set(level, x, 4 + k, z1 + 1 - k, Blocks.DARK_OAK_STAIRS.defaultBlockState()
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH).setValue(BlockStateProperties.HALF, Half.BOTTOM));
            }
            for (int z = z0 + k; z <= z1 - k; z++) {
                this.set(level, x0, 4 + k, z, Blocks.SPRUCE_PLANKS);
                this.set(level, x1, 4 + k, z, Blocks.SPRUCE_PLANKS);
            }
        }
        for (int x = x0 - 1; x <= x1 + 1; x++) {
            this.set(level, x, 7, zc, Blocks.DARK_OAK_SLAB);
        }
        // chimney
        for (int y = 0; y < 9; y++) {
            this.set(level, x1 - 1, y, z0 + 1, y < 4 ? Blocks.AIR : Blocks.COBBLESTONE);
        }
        this.set(level, x1 - 1, 9, z0 + 1, Blocks.CAMPFIRE);
        // inside: bed-ish, chest, furnace
        this.set(level, x0 + 1, 0, z0 + 1, Blocks.CHEST);
        this.set(level, x0 + 1, 0, z0 + 2, Blocks.FURNACE);
        this.set(level, x1 - 1, 0, z1 - 1, Blocks.BARREL);
        // outside work corner
        this.set(level, -6, 0, -4, Blocks.CRAFTING_TABLE);
        this.set(level, -6, 0, -6, Blocks.FURNACE.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
        this.set(level, -6, 0, -5, Blocks.CHEST.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
        this.set(level, -6, 0, -7, Blocks.HAY_BLOCK);
        this.set(level, -6, 1, -7, Blocks.HAY_BLOCK);
        this.set(level, -5, 0, -8, Blocks.HAY_BLOCK);
    }

    private void farm(ServerLevel level) {
        this.clearPlants(level, -15, -1, -7, 8);
        for (int x = -14; x <= -8; x++) {
            for (int z = 0; z <= 7; z++) {
                if (x == -11) {
                    this.set(level, x, -1, z, Blocks.WATER);
                    continue;
                }
                this.set(level, x, -1, z, Blocks.FARMLAND.defaultBlockState().setValue(BlockStateProperties.MOISTURE, 7));
                this.set(level, x, 0, z, Blocks.WHEAT.defaultBlockState().setValue(BlockStateProperties.AGE_7, z % 3 == 0 ? 5 : 7));
            }
        }
        for (int x = -15; x <= -7; x++) {
            this.set(level, x, 0, -1, x == -11 ? Blocks.OAK_FENCE_GATE.defaultBlockState() : Blocks.OAK_FENCE.defaultBlockState());
            this.set(level, x, 0, 8, Blocks.OAK_FENCE);
        }
        for (int z = 0; z <= 7; z++) {
            this.set(level, -15, 0, z, Blocks.OAK_FENCE);
            this.set(level, -7, 0, z, Blocks.OAK_FENCE);
        }
        this.set(level, -15, 1, 8, Blocks.TORCH);
        this.set(level, -7, 1, 8, Blocks.TORCH);
        this.set(level, -16, 0, 3, Blocks.COMPOSTER);
    }

    /** Training pen in front of the cabin. The main tester stands at (2, 0, 4). */
    private void pen(ServerLevel level) {
        this.clearPlants(level, -5, -3, 10, 12);
        for (int x = -4; x <= 9; x++) {
            for (int z = -2; z <= 11; z++) {
                boolean edge = x == -4 || x == 9 || z == -2 || z == 11;
                if (edge) {
                    boolean gate = z == 11 && (x == 2 || x == 3);
                    this.set(level, x, 0, z, gate ? Blocks.AIR.defaultBlockState() : Blocks.OAK_FENCE.defaultBlockState());
                    if (!gate && (x == -4 || x == 9) && (z == -2 || z == 11)) {
                        this.set(level, x, 1, z, Blocks.LANTERN);
                    }
                } else if (z >= 3 && z <= 6 || x >= 1 && x <= 4) {
                    this.set(level, x, -1, z, Blocks.DIRT_PATH);
                }
            }
        }
        for (int z = 12; z <= 22; z++) {
            this.set(level, 2, -1, z, Blocks.DIRT_PATH);
            this.set(level, 3, -1, z, Blocks.DIRT_PATH);
        }
        this.set(level, 8, 0, 10, Blocks.HAY_BLOCK);
        this.set(level, 7, 0, 10, Blocks.HAY_BLOCK);
        this.set(level, 8, 1, 10, Blocks.HAY_BLOCK);
        this.set(level, -3, 0, 10, Blocks.BARREL);
        this.set(level, -3, 1, 10, Blocks.LANTERN);
        // the damage counter next to the main tester
        this.set(level, 4, 0, 4, ModContent.DAMAGE_COUNTER.get().defaultBlockState().setValue(DamageCounterBlock.FACING, Direction.SOUTH));
    }

    /** Target block + 15 dust + a lamp; the tester stands on the target at (12, 1, 4). */
    private void redstone(ServerLevel level) {
        this.clearPlants(level, 10, 1, 30, 7);
        for (int x = 11; x <= 29; x++) {
            for (int z = 2; z <= 6; z++) {
                this.set(level, x, -1, z, z == 4 || x == 11 || x == 29 ? Blocks.SMOOTH_STONE.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState());
            }
        }
        this.set(level, 12, 0, 4, Blocks.TARGET);
        for (int x = 13; x <= 27; x++) {
            this.set(level, x, 0, 4, Blocks.REDSTONE_WIRE);
        }
        this.set(level, 28, 0, 4, Blocks.REDSTONE_LAMP);
        this.set(level, 29, 0, 4, Blocks.REDSTONE_LAMP);
        this.set(level, 28, 1, 4, Blocks.REDSTONE_LAMP);
    }

    public static final int ARENA_Z = 40;
    public static final int[] ARENA_X = {-8, -4, 0, 4, 8};

    private void arena(ServerLevel level) {
        this.clearPlants(level, -16, 22, 16, 48);
        for (int x = -14; x <= 14; x++) {
            for (int z = 24; z <= 46; z++) {
                boolean edge = x == -14 || x == 14 || z == 24 || z == 46;
                if (edge) {
                    boolean gate = z == 24 && x >= -1 && x <= 1;
                    if (!gate) {
                        this.set(level, x, 0, z, Blocks.SPRUCE_FENCE);
                        if ((x + 14) % 7 == 0 && (z == 24 || z == 46) || (z - 24) % 11 == 0 && (x == -14 || x == 14)) {
                            this.set(level, x, 1, z, Blocks.LANTERN);
                        }
                    }
                } else {
                    boolean path = z >= 37 && z <= 41 || x >= -1 && x <= 1;
                    this.set(level, x, -1, z, path ? Blocks.DIRT_PATH.defaultBlockState()
                            : (x * 7 + z * 3) % 5 == 0 ? Blocks.COARSE_DIRT.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState());
                }
            }
        }
        for (int z = 12; z < 24; z++) {
            this.set(level, 0, -1, z, Blocks.DIRT_PATH);
            this.set(level, 1, -1, z, Blocks.DIRT_PATH);
            this.set(level, -1, -1, z, Blocks.DIRT_PATH);
        }
        // hay corners
        for (int[] h : new int[][]{{-13, 45}, {-12, 45}, {-13, 44}, {13, 25}, {12, 25}, {13, 26}}) {
            this.set(level, h[0], 0, h[1], Blocks.HAY_BLOCK);
        }
        this.set(level, -13, 1, 45, Blocks.HAY_BLOCK);
        // gallows for two hanging testers: beam at y=4 over x 9..13, z 44
        for (int y = 0; y < 4; y++) {
            this.set(level, 8, y, 44, Blocks.OAK_LOG);
            this.set(level, 13, y, 44, Blocks.OAK_LOG);
        }
        for (int x = 8; x <= 13; x++) {
            this.set(level, x, 4, 44, Blocks.SPRUCE_PLANKS);
        }
        // weapon racks
        this.set(level, -13, 0, 30, Blocks.BARREL);
        this.set(level, -13, 0, 31, Blocks.BARREL);
        this.set(level, -13, 1, 30, Blocks.LANTERN);
        this.set(level, -12, 0, 26, Blocks.ANVIL);
        this.set(level, -13, 0, 27, Blocks.GRINDSTONE);
    }

    public static final int LOOP_X0 = 20;
    public static final int LOOP_X1 = 42;
    public static final int LOOP_Z0 = 26;
    public static final int LOOP_Z1 = 42;

    private void cartLoop(ServerLevel level) {
        this.clearPlants(level, LOOP_X0 - 2, LOOP_Z0 - 2, LOOP_X1 + 2, LOOP_Z1 + 2);
        int x0 = LOOP_X0;
        int x1 = LOOP_X1;
        int z0 = LOOP_Z0;
        int z1 = LOOP_Z1;
        // gravel bed
        for (int x = x0 - 1; x <= x1 + 1; x++) {
            for (int z = z0 - 1; z <= z1 + 1; z++) {
                boolean ring = x <= x0 + 1 || x >= x1 - 1 || z <= z0 + 1 || z >= z1 - 1;
                if (ring) {
                    this.set(level, x, -1, z, Blocks.GRAVEL);
                }
            }
        }
        // straights
        for (int x = x0 + 1; x < x1; x++) {
            this.rail(level, x, z0, RailShape.EAST_WEST, (x - x0) % 3 == 0);
            this.rail(level, x, z1, RailShape.EAST_WEST, (x - x0) % 3 == 0);
        }
        for (int z = z0 + 1; z < z1; z++) {
            this.rail(level, x0, z, RailShape.NORTH_SOUTH, (z - z0) % 3 == 0);
            this.rail(level, x1, z, RailShape.NORTH_SOUTH, (z - z0) % 3 == 0);
        }
        this.rail(level, x0, z0, RailShape.SOUTH_EAST, false);
        this.rail(level, x1, z0, RailShape.SOUTH_WEST, false);
        this.rail(level, x1, z1, RailShape.NORTH_WEST, false);
        this.rail(level, x0, z1, RailShape.NORTH_EAST, false);
        // a little platform in the middle for the archers
        for (int x = 28; x <= 34; x++) {
            for (int z = 31; z <= 37; z++) {
                this.set(level, x, -1, z, Blocks.SPRUCE_PLANKS);
            }
        }
        this.set(level, 28, 0, 31, Blocks.LANTERN);
        this.set(level, 34, 0, 37, Blocks.LANTERN);
        this.set(level, 34, 0, 31, Blocks.TARGET);
    }

    private void rail(ServerLevel level, int x, int z, RailShape shape, boolean powered) {
        if (powered) {
            this.set(level, x, -1, z, Blocks.REDSTONE_BLOCK);
            level.setBlock(this.p(x, 0, z), Blocks.POWERED_RAIL.defaultBlockState()
                    .setValue(BlockStateProperties.RAIL_SHAPE_STRAIGHT, shape).setValue(BlockStateProperties.POWERED, true), 2);
        } else {
            level.setBlock(this.p(x, 0, z), Blocks.RAIL.defaultBlockState().setValue(BlockStateProperties.RAIL_SHAPE, shape), 2);
        }
    }

    /** Showroom row for skins, x -43..-25 step 3 at z=32. */
    public static final int SHOW_Z = 32;

    private void showroom(ServerLevel level) {
        this.clearPlants(level, -47, 26, -21, 38);
        for (int x = -46; x <= -22; x++) {
            for (int z = 30; z <= 34; z++) {
                this.set(level, x, -1, z, z == 32 ? Blocks.DIRT_PATH.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState());
            }
            this.set(level, x, 0, 28, Blocks.OAK_FENCE);
            if (x % 4 == 0) {
                this.set(level, x, 1, 28, Blocks.LANTERN);
            }
        }
        for (int x = -45; x <= -23; x += 5) {
            this.set(level, x, 0, 29, Blocks.HAY_BLOCK);
        }
        this.set(level, -46, 0, 29, Blocks.CARVED_PUMPKIN);
        this.set(level, -22, 0, 29, Blocks.JACK_O_LANTERN);
    }
}
