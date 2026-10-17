package com.example.examplemod;
\\ This will beversion 1.0

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

@Mod(ExampleMod.MODID)
public class ExampleMod {
    public static final String MODID = "livingecosystem";

    private static final double GROUP_RADIUS = 7.0D;
    private static final double SEARCH_RADIUS = 125.0D;
    private static final int REQUIRED_COMPANIONS = 2;

    private static final double MOVEMENT_SPEED = 1.4D;
    private static final int REPATH_INTERVAL = 10;

    private static final String HERD_LEADER = "le_herd_leader";
    private static final String HOME_X = "le_home_x";
    private static final String HOME_Y = "le_home_y";
    private static final String HOME_Z = "le_home_z";
    private static final String HOME_SET = "le_home_set";

    public ExampleMod(FMLJavaModLoadingContext context) {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel)) {
            return;
        }

        if (event.getEntity() instanceof Sheep sheep) {
            sheep.goalSelector.addGoal(5, new SheepFlockGoal(sheep));
        }
    }

    private static void initializeHerd(Sheep sheep) {
        CompoundTag data = sheep.getPersistentData();

        if (data.hasUUID(HERD_LEADER)) {
            ensureHomeData(sheep);
            return;
        }

        List<Sheep> candidates = sheep.level().getEntitiesOfClass(
                Sheep.class,
                sheep.getBoundingBox().inflate(SEARCH_RADIUS),
                other -> other != sheep
                        && other.isAlive()
                        && other.getPersistentData()
                        .hasUUID(HERD_LEADER)
        );

        Sheep nearest = candidates.stream()
                .min(Comparator.comparingDouble(
                        sheep::distanceToSqr))
                .orElse(null);

        if (nearest != null) {
            UUID leaderId = nearest.getPersistentData()
                    .getUUID(HERD_LEADER);

            data.putUUID(HERD_LEADER, leaderId);
            copyHomeData(nearest, sheep);
        } else {
            // Establish a new herd and mark its home.
            data.putUUID(HERD_LEADER, sheep.getUUID());

            data.putDouble(HOME_X, sheep.getX());
            data.putDouble(HOME_Y, sheep.getY());
            data.putDouble(HOME_Z, sheep.getZ());
            data.putBoolean(HOME_SET, true);

            markHomePath(sheep);
        }
    }

    /*
     * Creates a visible 3x3 home marker using dirt path.
     * Only natural ground blocks are changed.
     */
    private static void markHomePath(Sheep sheep) {
        if (!(sheep.level() instanceof ServerLevel level)) {
            return;
        }

        // Entity Y is near the sheep's feet. Find the ground
        // block directly underneath its original home position.
        BlockPos center = BlockPos.containing(
                sheep.getX(),
                sheep.getY() - 0.1D,
                sheep.getZ()
        );

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos pos = center.offset(dx, 0, dz);
                BlockState state = level.getBlockState(pos);

                boolean naturalGround =
                        state.is(Blocks.GRASS_BLOCK)
                                || state.is(Blocks.DIRT)
                                || state.is(Blocks.COARSE_DIRT)
                                || state.is(Blocks.ROOTED_DIRT)
                                || state.is(Blocks.PODZOL)
                                || state.is(Blocks.MYCELIUM);

                if (naturalGround
                        && level.getBlockState(pos.above()).isAir()) {
                    level.setBlock(
                            pos,
                            Blocks.DIRT_PATH.defaultBlockState(),
                            3
                    );
                }
            }
        }
    }

    private static void ensureHomeData(Sheep sheep) {
        CompoundTag data = sheep.getPersistentData();

        if (data.getBoolean(HOME_SET)) {
            return;
        }

        data.putDouble(HOME_X, sheep.getX());
        data.putDouble(HOME_Y, sheep.getY());
        data.putDouble(HOME_Z, sheep.getZ());
        data.putBoolean(HOME_SET, true);
    }

    private static void copyHomeData(
            Sheep source, Sheep destination) {

        CompoundTag sourceData = source.getPersistentData();
        CompoundTag destinationData = destination.getPersistentData();

        if (sourceData.getBoolean(HOME_SET)) {
            destinationData.putDouble(
                    HOME_X, sourceData.getDouble(HOME_X));
            destinationData.putDouble(
                    HOME_Y, sourceData.getDouble(HOME_Y));
            destinationData.putDouble(
                    HOME_Z, sourceData.getDouble(HOME_Z));
            destinationData.putBoolean(HOME_SET, true);
        } else {
            ensureHomeData(destination);
        }
    }

    private static boolean belongsToSameHerd(
            Sheep first, Sheep second) {

        CompoundTag firstData = first.getPersistentData();
        CompoundTag secondData = second.getPersistentData();

        return firstData.hasUUID(HERD_LEADER)
                && secondData.hasUUID(HERD_LEADER)
                && firstData.getUUID(HERD_LEADER).equals(
                secondData.getUUID(HERD_LEADER));
    }

    private static List<Sheep> nearbyHerdMembers(
            Sheep sheep, double radius) {

        double radiusSquared = radius * radius;

        return sheep.level().getEntitiesOfClass(
                Sheep.class,
                sheep.getBoundingBox().inflate(radius),
                other -> other != sheep
                        && other.isAlive()
                        && belongsToSameHerd(sheep, other)
                        && sheep.distanceToSqr(other) <= radiusSquared
        );
    }

    private static boolean hasEnoughCompanions(Sheep sheep) {
        return nearbyHerdMembers(sheep, GROUP_RADIUS).size()
                >= REQUIRED_COMPANIONS;
    }

    private static boolean isLeader(Sheep sheep) {
        CompoundTag data = sheep.getPersistentData();

        return data.hasUUID(HERD_LEADER)
                && data.getUUID(HERD_LEADER).equals(sheep.getUUID());
    }

    @SubscribeEvent
    public void onSheepDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Sheep deadSheep)
                || !(deadSheep.level() instanceof ServerLevel)) {
            return;
        }

        if (!deadSheep.getPersistentData().hasUUID(HERD_LEADER)
                || !isLeader(deadSheep)) {
            return;
        }

        UUID oldLeaderId = deadSheep.getUUID();

        List<Sheep> members = deadSheep.level().getEntitiesOfClass(
                Sheep.class,
                deadSheep.getBoundingBox().inflate(SEARCH_RADIUS),
                other -> other != deadSheep
                        && other.isAlive()
                        && other.getPersistentData().hasUUID(HERD_LEADER)
                        && other.getPersistentData().getUUID(HERD_LEADER)
                        .equals(oldLeaderId)
        );

        Sheep newLeader = members.stream()
                .min(Comparator.comparingDouble(
                        deadSheep::distanceToSqr))
                .orElse(null);

        if (newLeader == null) {
            return;
        }

        UUID newLeaderId = newLeader.getUUID();

        for (Sheep member : members) {
            member.getPersistentData().putUUID(
                    HERD_LEADER, newLeaderId);
        }

        newLeader.getPersistentData().putUUID(
                HERD_LEADER, newLeaderId);

        copyHomeData(deadSheep, newLeader);
    }

    private static class SheepFlockGoal extends Goal {
        private final Sheep sheep;

        private Sheep target;
        private int repathDelay;

        private SheepFlockGoal(Sheep sheep) {
            this.sheep = sheep;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            target = null;

            if (!sheep.isAlive() || sheep.isInWaterOrBubble()) {
                return false;
            }

            if (!sheep.getPersistentData().hasUUID(HERD_LEADER)) {
                initializeHerd(sheep);
            } else {
                ensureHomeData(sheep);
            }

            if (isLeader(sheep) || hasEnoughCompanions(sheep)) {
                return false;
            }

            UUID leaderId = sheep.getPersistentData()
                    .getUUID(HERD_LEADER);

            target = sheep.level().getEntitiesOfClass(
                            Sheep.class,
                            sheep.getBoundingBox().inflate(SEARCH_RADIUS),
                            other -> other != sheep
                                    && other.isAlive()
                                    && other.getUUID().equals(leaderId)
                                    && sheep.distanceToSqr(other)
                                    <= SEARCH_RADIUS * SEARCH_RADIUS
                    ).stream()
                    .findFirst()
                    .orElse(null);

            if (target == null) {
                target = sheep.level().getEntitiesOfClass(
                                Sheep.class,
                                sheep.getBoundingBox().inflate(SEARCH_RADIUS),
                                other -> other != sheep
                                        && other.isAlive()
                                        && belongsToSameHerd(sheep, other)
                        ).stream()
                        .min(Comparator.comparingDouble(sheep::distanceToSqr))
                        .orElse(null);
            }

            return target != null;
        }

        @Override
        public boolean canContinueToUse() {
            if (!sheep.isAlive()
                    || sheep.isInWaterOrBubble()
                    || isLeader(sheep)
                    || hasEnoughCompanions(sheep)) {
                return false;
            }

            return target != null
                    && target.isAlive()
                    && belongsToSameHerd(sheep, target);
        }

        @Override
        public void start() {
            repathDelay = 0;
        }

        @Override
        public void tick() {
            if (target == null || !target.isAlive()) {
                return;
            }

            sheep.getLookControl().setLookAt(target);

            if (sheep.level() instanceof ServerLevel serverLevel
                    && sheep.tickCount % 10 == 0) {
                serverLevel.sendParticles(
                        ParticleTypes.ANGRY_VILLAGER,
                        sheep.getX(),
                        sheep.getY() + sheep.getBbHeight() + 0.15D,
                        sheep.getZ(),
                        1,
                        0.0D, 0.0D, 0.0D,
                        0.0D
                );
            }

            if (--repathDelay <= 0) {
                repathDelay = REPATH_INTERVAL;

                sheep.getNavigation().moveTo(
                        target,
                        MOVEMENT_SPEED
                );
            }
        }

        @Override
        public void stop() {
            sheep.getNavigation().stop();
            target = null;
        }
    }
}