package com.scarasol.acceleratednavigation;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.monster.Husk;

/** A session-owned adult husk; vanilla navigation and physics remain active. */
final class NavigationTestMob extends Husk {
    NavigationTestMob(ServerLevel level, boolean canOpenDoors) {
        super(EntityType.HUSK, level);
        setBaby(false);
        setPersistenceRequired();
        setInvulnerable(true);
        setCanPickUpLoot(false);
        ((GroundPathNavigation) getNavigation()).setCanOpenDoors(canOpenDoors);
        if (canOpenDoors) goalSelector.addGoal(1, new OpenDoorGoal(this, true));
        addEffect(new MobEffectInstance(MobEffects.GLOWING, MobEffectInstance.INFINITE_DURATION,
                0, false, false));
        addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, MobEffectInstance.INFINITE_DURATION,
                4, false, false));
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
    }

    @Override
    protected boolean shouldDespawnInPeaceful() {
        return false;
    }

    @Override
    protected boolean convertsInWater() {
        return false;
    }

    @Override
    public boolean canChangeDimensions() {
        return false;
    }

    // An unloaded or stopped session must not leave an autonomous husk in the save.
    @Override
    public boolean shouldBeSaved() {
        return false;
    }
}
