package org.dawnoftime.onceuponatown.entity.ai.toolsmith;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import org.dawnoftime.onceuponatown.datapack.ToolsmithConfigDataHandler;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.entity.ai.standbased.AbstractStandBasedJob;
import org.dawnoftime.onceuponatown.entity.ai.shared.StandJobConfig;
import org.dawnoftime.onceuponatown.town.StandSlot;
import org.dawnoftime.onceuponatown.town.Town;

import java.util.List;

public class ToolsmithJob extends AbstractStandBasedJob {

    private int smithAnimCounter = 0;
    private int smithDelayTarget = 20;

    public ToolsmithJob(Npc npc) { super(npc); }

    @Override public String getJobId() { return "toolsmith"; }

    @Override
    protected boolean canTradeNow() {
        return baseState != NpcBaseState.SLEEPING && !npc.getNavigation().isInProgress();
    }

    @Override
    protected boolean shouldLookAtPlayer() {
        return baseState != NpcBaseState.SLEEPING;
    }

    @Override
    protected @Nullable StandJobConfig resolveConfig() {
        return ToolsmithConfigDataHandler.get();
    }

    @Override
    public List<String> getProductionBuildings() {
        ToolsmithConfigDataHandler.Config cfg = ToolsmithConfigDataHandler.get();
        return cfg == null ? List.of() : cfg.productionBuildings;
    }

    @Override
    protected void onArrivalAtStand() {
        smithAnimCounter = 0;
        recomputeSmithDelay();
        npc.holdInMainHand(new ItemStack(Items.STONE_PICKAXE));
    }

    @Override
    protected void tickAtWork(Town town, StandJobConfig cfg) {
        npc.getNavigation().stop();
        if (cachedLookPlayer == null && heldSlot != null) {
            BlockPos target = heldSlot.associatedBlockPos != null ? heldSlot.associatedBlockPos : heldSlot.position;
            npc.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, 10f, 10f);
        }
        smithAnimCounter++;
        if (smithAnimCounter >= smithDelayTarget) {
            smithAnimCounter = 0;
            recomputeSmithDelay();
            npc.swing(InteractionHand.MAIN_HAND);
            npc.notifyBlockPlaced();
        }
    }

    private void recomputeSmithDelay() {
        ToolsmithConfigDataHandler.Config cfg = ToolsmithConfigDataHandler.get();
        if (cfg == null) { smithDelayTarget = 20; return; }
        float speed = cfg.smithingSpeedMin + npc.getRandom().nextFloat() * (cfg.smithingSpeedMax - cfg.smithingSpeedMin);
        smithDelayTarget = speedToTicks(speed);
    }
}
