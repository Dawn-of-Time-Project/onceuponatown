package org.dawnoftime.onceuponatown.entity.ai.merchant;

import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.dawnoftime.onceuponatown.town.ItemCost;
import org.dawnoftime.onceuponatown.town.Town;

import javax.annotation.Nullable;
import java.util.List;
import java.util.OptionalInt;

public final class SimpleTownMerchant implements Merchant {

    private final MerchantOffers offers;
    private Player tradingPlayer;

    @Nullable private final Town town;
    private final Runnable markDirty;

    public SimpleTownMerchant(MerchantOffers offers, @Nullable Town town, Runnable markDirty) {
        this.offers = offers;
        this.town = town;
        this.markDirty = markDirty;
    }

    @Override public MerchantOffers getOffers()             { return offers; }
    @Override public void setTradingPlayer(Player p)        { tradingPlayer = p; }
    @Override public Player getTradingPlayer()              { return tradingPlayer; }
    @Override public void notifyTradeUpdated(ItemStack s)   {}
    @Override public void overrideOffers(MerchantOffers o)  {}
    @Override public int getVillagerXp()                    { return 0; }
    @Override public void overrideXp(int xp)                {}
    @Override public boolean showProgressBar()              { return false; }
    @Override public SoundEvent getNotifyTradeSound()       { return SoundEvents.VILLAGER_YES; }
    @Override public boolean isClientSide()                 { return false; }

    @Override
    public void notifyTrade(MerchantOffer offer) {
        offer.increaseUses();
        if (town == null) return;

        ItemStack costA = offer.getCostA();
        ItemStack result = offer.getResult();

        if (costA.is(Items.EMERALD)) {
            // Player bought: remove result item from town stock
            town.getTownInventory().removeStock(
                List.of(new ItemCost(result.getItem(), result.getCount()))
            );
        } else {
            // Player sold: add costA item to town stock (capacity-guarded internally)
            town.tryAddToStockUnchecked(costA.getItem(), costA.getCount());
        }

        markDirty.run();
    }

    @Override
    public void openTradingScreen(Player player, Component title, int level) {
        OptionalInt id = player.openMenu(new SimpleMenuProvider(
            (syncId, inv, p) -> new TownMerchantMenu(syncId, inv, this), title));
        if (id.isPresent()) {
            MerchantOffers offers = this.getOffers();
            if (!offers.isEmpty()) {
                player.sendMerchantOffers(id.getAsInt(), offers, level,
                    this.getVillagerXp(), this.showProgressBar(), this.canRestock());
            }
        }
    }
}
