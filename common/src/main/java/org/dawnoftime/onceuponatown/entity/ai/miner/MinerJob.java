package org.dawnoftime.onceuponatown.entity.ai.miner;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.dawnoftime.onceuponatown.datapack.MinerConfigDataHandler;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.entity.ai.standbased.AbstractStandBasedJob;
import org.dawnoftime.onceuponatown.entity.ai.shared.StandJobConfig;
import org.dawnoftime.onceuponatown.town.StandSlot;
import org.dawnoftime.onceuponatown.town.Town;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class MinerJob extends AbstractStandBasedJob {

    private int mineAnimCounter = 0;
    private int mineDelayTarget = 20;

    public MinerJob(Npc npc) { super(npc); }

    @Override public String getJobId() { return "miner"; }

    @Override
    protected @Nullable StandJobConfig resolveConfig() {
        return MinerConfigDataHandler.get();
    }

    @Override
    public List<String> getProductionBuildings() {
        MinerConfigDataHandler.Config cfg = MinerConfigDataHandler.get();
        return cfg == null ? List.of() : cfg.productionBuildings;
    }

    // Re-equip and reset animation on every arrival, covering fresh claims, post-eat and post-sleep returns.
    @Override
    protected void onArrivalAtStand() {
        mineAnimCounter = 0;
        recomputeMineDelay();
        if (heldSlot != null) equipFromSlot(heldSlot);
    }

    @Override
    protected void tickAtWork(Town town, StandJobConfig cfg) {
        npc.getNavigation().stop();
        if (cachedLookPlayer == null && heldSlot != null) {
            net.minecraft.core.BlockPos target = heldSlot.associatedBlockPos != null ? heldSlot.associatedBlockPos : heldSlot.position;
            npc.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, 10f, 10f);
        }
        mineAnimCounter++;
        if (mineAnimCounter >= mineDelayTarget) {
            mineAnimCounter = 0;
            recomputeMineDelay();
            npc.swing(InteractionHand.MAIN_HAND);
            npc.notifyBlockPlaced();
        }
    }

    private void recomputeMineDelay() {
        MinerConfigDataHandler.Config cfg = MinerConfigDataHandler.get();
        if (cfg == null) { mineDelayTarget = 20; return; }
        float speed = cfg.mineSpeedMin + npc.getRandom().nextFloat() * (cfg.mineSpeedMax - cfg.mineSpeedMin);
        mineDelayTarget = speedToTicks(speed);
    }

    private void equipFromSlot(StandSlot slot) {
        if (slot.toolItem != null) {
            BuiltInRegistries.ITEM.getOptional(new ResourceLocation(slot.toolItem))
                .ifPresent(item -> npc.holdInMainHand(new ItemStack(item)));
        } else {
            npc.holdInMainHand(new ItemStack(Items.WOODEN_PICKAXE));
        }
    }
}
