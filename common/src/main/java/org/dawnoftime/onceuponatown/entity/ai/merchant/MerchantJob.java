package org.dawnoftime.onceuponatown.entity.ai.merchant;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.dawnoftime.onceuponatown.datapack.BuildingDataHandler;
import org.dawnoftime.onceuponatown.datapack.MerchantConfigDataHandler;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.entity.ai.standbased.AbstractStandBasedJob;
import org.dawnoftime.onceuponatown.entity.ai.shared.StandJobConfig;
import org.dawnoftime.onceuponatown.item.CommerceContractItem;
import org.dawnoftime.onceuponatown.registry.ItemRegistry;
import org.dawnoftime.onceuponatown.town.ContractEntry;
import org.dawnoftime.onceuponatown.town.LevelTowns;
import org.dawnoftime.onceuponatown.town.Town;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class MerchantJob extends AbstractStandBasedJob {

    public MerchantJob(Npc npc) { super(npc); }

    @Override public String getJobId() { return "merchant"; }

    @Override
    protected boolean canTradeNow() {
        return baseState != NpcBaseState.SLEEPING && !npc.getNavigation().isInProgress();
    }

    @Override
    protected TradeScope getTradeScope() { return TradeScope.VILLAGE; }

    @Override
    protected boolean shouldLookAtPlayer() {
        return baseState != NpcBaseState.SLEEPING;
    }

    @Override
    protected @Nullable StandJobConfig resolveConfig() {
        return MerchantConfigDataHandler.get();
    }

    // Merchant releases its stand before eating and on RESYNC (both are the defaults in AbstractStandBasedJob).

    @Override
    protected void tickAtWork(Town town, StandJobConfig cfg) {
        if (cachedLookPlayer == null && heldSlot != null) {
            BlockPos target = heldSlot.associatedBlockPos != null ? heldSlot.associatedBlockPos : heldSlot.position;
            npc.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, 10f, 10f);
        }
    }

    @Override
    public InteractionResult onPlayerInteract(Player player) {
        if (!canTradeNow()) return InteractionResult.PASS;
        if (!(npc.level() instanceof ServerLevel serverLevel)) return InteractionResult.PASS;

        Town town = findTown(serverLevel, npc);
        if (town == null) return InteractionResult.PASS;

        Runnable markDirty = () -> LevelTowns.get(serverLevel).markDirty();
        MerchantOffers offers = buildMarketOffers(town, null);
        double contractRatio = town.getBuildings().stream()
            .filter(b -> "market".equals(b.defId))
            .mapToDouble(b -> BuildingDataHandler.get(b.defId)
                .map(def -> (double) def.resolveAtLevel(b.getUpgradeLevel()).resolvedContractRatio())
                .orElse(BASE_CONTRACT_RATIO))
            .max()
            .orElse(BASE_CONTRACT_RATIO);
        List<ContractEntry> snapshot = snapshotProduction(town, contractRatio);
        if (!snapshot.isEmpty()) {
            ItemStack contractStack = new ItemStack(ItemRegistry.COMMERCE_CONTRACT);
            CommerceContractItem.writeEntries(contractStack, snapshot);
            offers.add(new MerchantOffer(
                new ItemStack(Items.EMERALD, CONTRACT_TRADE_PRICE),
                contractStack,
                Integer.MAX_VALUE,
                0,
                1.0f
            ));
        }
        SimpleTownMerchant merchant = new SimpleTownMerchant(offers, town, markDirty);
        merchant.setTradingPlayer(player);
        merchant.openTradingScreen(player, getTradeTitle(), 1);
        return InteractionResult.SUCCESS;
    }
}
