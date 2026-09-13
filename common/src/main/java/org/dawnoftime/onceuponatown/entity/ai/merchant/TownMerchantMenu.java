package org.dawnoftime.onceuponatown.entity.ai.merchant;

import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.Merchant;

// Extends MerchantMenu solely to override quickMoveStack.
// Vanilla playTradeSound() hard-casts the Merchant to Entity, which crashes
// for non-entity merchants like SimpleTownMerchant. We replace that call
// with a safe version that plays the sound at the player's position instead.
public class TownMerchantMenu extends MerchantMenu {

    private final Merchant merchant;

    public TownMerchantMenu(int containerId, Inventory playerInventory, Merchant merchant) {
        super(containerId, playerInventory, merchant);
        this.merchant = merchant;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack copy = ItemStack.EMPTY;
        Slot slot = this.getSlot(index);

        if (!slot.hasItem()) return copy;

        ItemStack slotStack = slot.getItem();
        copy = slotStack.copy();

        if (index == 2) {
            if (!this.moveItemStackTo(slotStack, 3, 39, true)) return ItemStack.EMPTY;
            slot.onQuickCraft(slotStack, copy);
            this.playTradeSoundSafe(player);
        } else if (index == 0 || index == 1) {
            if (!this.moveItemStackTo(slotStack, 3, 39, false)) return ItemStack.EMPTY;
        } else if (index >= 3 && index < 30) {
            if (!this.moveItemStackTo(slotStack, 30, 39, false)) return ItemStack.EMPTY;
        } else if (index >= 30 && index < 39) {
            if (!this.moveItemStackTo(slotStack, 3, 30, false)) return ItemStack.EMPTY;
        }

        if (slotStack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }

        if (slotStack.getCount() == copy.getCount()) return ItemStack.EMPTY;

        slot.onTake(player, slotStack);
        return copy;
    }

    private void playTradeSoundSafe(Player player) {
        if (!player.level().isClientSide()) {
            player.level().playLocalSound(
                player.getX(), player.getY(), player.getZ(),
                this.merchant.getNotifyTradeSound(),
                SoundSource.NEUTRAL, 1.0f, 1.0f, false
            );
        }
    }
}
