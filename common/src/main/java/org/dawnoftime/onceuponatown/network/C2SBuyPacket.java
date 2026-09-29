package org.dawnoftime.onceuponatown.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.dawnoftime.onceuponatown.Ouat;
import org.dawnoftime.onceuponatown.blockentity.TownAnchorBlockEntity;
import org.dawnoftime.onceuponatown.datapack.TradePriceDataHandler;
import org.dawnoftime.onceuponatown.screen.TownHubMenu;
import org.dawnoftime.onceuponatown.town.ItemCost;
import org.dawnoftime.onceuponatown.town.LevelTowns;
import org.dawnoftime.onceuponatown.town.Town;

import java.util.ArrayList;
import java.util.List;

// Sent by client when the player confirms a purchase in BUY mode.
// Carries the list of (itemId, count) pairs staged in the blue zone.
public record C2SBuyPacket(BlockPos anchorPos, List<Entry> requested) {
    public static final ResourceLocation ID = Ouat.modResource("c2s_buy");

    public record Entry(String itemId, int count) {}

    public static C2SBuyPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int size = buf.readVarInt();
        List<Entry> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(new Entry(buf.readUtf(), buf.readVarInt()));
        }
        return new C2SBuyPacket(pos, list);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(anchorPos);
        buf.writeVarInt(requested.size());
        for (Entry e : requested) {
            buf.writeUtf(e.itemId());
            buf.writeVarInt(e.count());
        }
    }

    public static class Handler {
        public static void handle(C2SBuyPacket packet, ServerPlayer player) {
            ServerLevel level = (ServerLevel) player.level();
            if (!(level.getBlockEntity(packet.anchorPos()) instanceof TownAnchorBlockEntity)) return;
            if (!(player.containerMenu instanceof TownHubMenu)) return;

            Town town = LevelTowns.get(level).getTownAt(packet.anchorPos()).orElse(null);
            if (town == null) return;

            // Build validated purchase list and compute total emerald cost
            List<ItemStack> toGive = new ArrayList<>();
            List<ItemCost> toRemove = new ArrayList<>();
            int totalCost = 0;

            for (Entry entry : packet.requested()) {
                if (entry.count() <= 0) continue;
                ResourceLocation rl = ResourceLocation.tryParse(entry.itemId());
                if (rl == null) continue;
                Item item = BuiltInRegistries.ITEM.get(rl);
                if (item == null || item == Items.AIR) continue;
                int price    = TradePriceDataHandler.getBuyPrice(item);
                if (price <= 0) continue;
                int quantity = TradePriceDataHandler.getQuantity(item);
                int lots     = entry.count() / quantity;
                if (lots <= 0) continue;
                int actualCount = lots * quantity;
                if (!town.getTownInventory().hasStock(List.of(new ItemCost(item, actualCount)))) return;
                totalCost += price * lots;
                toGive.add(new ItemStack(item, actualCount));
                toRemove.add(new ItemCost(item, actualCount));
            }

            if (toGive.isEmpty()) return;

            if (!town.isPlayerControlled()) {
                // Verify player has enough emeralds
                int playerEmeralds = 0;
                for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                    ItemStack s = player.getInventory().getItem(i);
                    if (s.is(Items.EMERALD)) playerEmeralds += s.getCount();
                }
                if (playerEmeralds < totalCost) return;

                // Deduct emeralds from player
                int remaining = totalCost;
                for (int i = 0; i < player.getInventory().getContainerSize() && remaining > 0; i++) {
                    ItemStack s = player.getInventory().getItem(i);
                    if (!s.is(Items.EMERALD)) continue;
                    int remove = Math.min(remaining, s.getCount());
                    s.shrink(remove);
                    remaining -= remove;
                    if (s.isEmpty()) player.getInventory().setItem(i, ItemStack.EMPTY);
                }
            }

            // Remove from village stock and give items to player
            town.getTownInventory().removeStock(toRemove);
            for (ItemStack give : toGive) {
                player.addItem(give);
            }

            LevelTowns.get(level).markDirty();
            NetworkHelper.sendStockUpdatePacket.accept(player, town.getStockUpdateData(packet.anchorPos()));
        }
    }
}
