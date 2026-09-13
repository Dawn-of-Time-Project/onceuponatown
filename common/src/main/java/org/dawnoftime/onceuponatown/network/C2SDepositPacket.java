package org.dawnoftime.onceuponatown.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.dawnoftime.onceuponatown.Ouat;
import org.dawnoftime.onceuponatown.blockentity.TownAnchorBlockEntity;
import org.dawnoftime.onceuponatown.datapack.TradePriceDataHandler;
import org.dawnoftime.onceuponatown.item.CommerceContractItem;
import org.dawnoftime.onceuponatown.item.RecognitionMedalItem;
import org.dawnoftime.onceuponatown.registry.ItemRegistry;
import org.dawnoftime.onceuponatown.screen.TownHubMenu;
import org.dawnoftime.onceuponatown.town.ContractEntry;
import org.dawnoftime.onceuponatown.town.LevelTowns;
import org.dawnoftime.onceuponatown.town.Town;

import java.util.List;
import java.util.Set;

public record C2SDepositPacket(BlockPos anchorPos) {
    public static final ResourceLocation ID = Ouat.modResource("c2s_deposit");

    public static C2SDepositPacket decode(FriendlyByteBuf buf) {
        return new C2SDepositPacket(buf.readBlockPos());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(anchorPos);
    }

    public static class Handler {
        public static void handle(C2SDepositPacket packet, ServerPlayer player) {
            ServerLevel level = (ServerLevel) player.level();
            if (!(level.getBlockEntity(packet.anchorPos()) instanceof TownAnchorBlockEntity)) return;
            if (!(player.containerMenu instanceof TownHubMenu menu)) return;

            Town town = LevelTowns.get(level).getTownAt(packet.anchorPos()).orElse(null);
            if (town == null) return;
            Set<Item> productionItems = town.buildAcceptedItemSet();
            SimpleContainer deposit = menu.getDepositContainer();

            // Contract deposit: consume the item and activate virtual production for this village.
            for (int i = 0; i < deposit.getContainerSize(); i++) {
                ItemStack stack = deposit.getItem(i);
                if (stack.getItem() != ItemRegistry.COMMERCE_CONTRACT) continue;
                if (town.getActiveContract() != null) continue;
                List<ContractEntry> entries = CommerceContractItem.readEntries(stack);
                if (entries.isEmpty()) continue;

                town.setActiveContract(entries);
                deposit.setItem(i, ItemStack.EMPTY);
                LevelTowns.get(level).markDirty();
                NetworkHelper.sendStockUpdatePacket.accept(player, town.getStockUpdateData(packet.anchorPos()));
            }

            // Recognition Medal deposit: unlock signature buildings from another village of the same culture.
            for (int i = 0; i < deposit.getContainerSize(); i++) {
                ItemStack stack = deposit.getItem(i);
                if (stack.isEmpty() || stack.getItem() != ItemRegistry.RECOGNITION_MEDAL) continue;
                if (town.isMedalReceived()) continue;
                String medalNamespace = RecognitionMedalItem.readNamespace(stack);
                if (!medalNamespace.equals(town.getCultureNamespace())) continue;
                Set<String> ids = RecognitionMedalItem.readIds(stack);
                town.addUnlockedBuildingIds(ids);
                town.receiveMedal(ids);
                deposit.setItem(i, ItemStack.EMPTY);
                LevelTowns.get(level).markDirty();
                NetworkHelper.sendTownHubPacket.accept(player, town.getHubData(packet.anchorPos(), level));
                break;
            }

            boolean changed = false;
            int totalEmeralds = 0;
            for (int i = 0; i < deposit.getContainerSize(); i++) {
                ItemStack stack = deposit.getItem(i);
                if (stack.isEmpty()) continue;
                Item item = stack.getItem();
                if (!productionItems.contains(item)) continue;

                int count    = stack.getCount();
                int accepted = town.tryAddToStockUnchecked(item, count);
                if (accepted <= 0) continue;

                changed = true;
                int sellPrice = TradePriceDataHandler.getSellPrice(item);
                int quantity  = TradePriceDataHandler.getQuantity(item);
                if (sellPrice > 0) totalEmeralds += sellPrice * (accepted / quantity);

                int rejected = count - accepted;
                deposit.setItem(i, rejected > 0 ? new ItemStack(item, rejected) : ItemStack.EMPTY);
            }

            if (changed) {
                // Pay out emeralds in stacks of 64 so inventory handles it correctly
                int emeraldsLeft = totalEmeralds;
                while (emeraldsLeft > 0) {
                    int batch = Math.min(emeraldsLeft, 64);
                    ItemStack reward = new ItemStack(Items.EMERALD, batch);
                    if (!player.getInventory().add(reward)) player.drop(reward, false);
                    emeraldsLeft -= batch;
                }

                LevelTowns.get(level).markDirty();
                NetworkHelper.sendStockUpdatePacket.accept(player, town.getStockUpdateData(packet.anchorPos()));
            }
        }
    }
}
