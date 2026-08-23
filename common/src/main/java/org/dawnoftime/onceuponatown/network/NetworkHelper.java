package org.dawnoftime.onceuponatown.network;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.dawnoftime.onceuponatown.screen.TownHubMenu;
import org.dawnoftime.onceuponatown.town.Town;
import org.dawnoftime.onceuponatown.town.TownLogEntry;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

public class NetworkHelper {
    // S2C delegates (set by each platform server-side init)
    public static BiConsumer<ServerPlayer, CompoundTag> sendTownHubPacket       = (player, data) -> {};
    public static BiConsumer<ServerPlayer, CompoundTag> sendBuildingDefsPacket  = (player, data) -> {};
    public static BiConsumer<ServerPlayer, CompoundTag> sendStockUpdatePacket   = (player, data) -> {};
    public static BiConsumer<ServerPlayer, CompoundTag> sendBuildingListPacket  = (player, data) -> {};
    public static BiConsumer<ServerPlayer, CompoundTag> sendQuestUpdatePacket   = (player, data) -> {};
    public static BiConsumer<ServerPlayer, CompoundTag> sendEraUpdatePacket     = (player, data) -> {};
    public static BiConsumer<ServerPlayer, CompoundTag> sendCitizenUpdatePacket = (player, data) -> {};
    public static BiConsumer<ServerPlayer, CompoundTag> sendLogEntryPacket     = (player, data) -> {};

    // C2S delegates (set by each platform client-side init)
    public static Consumer<BlockPos>            sendToggleChatBroadcastPacket  = pos              -> {};
    public static BiConsumer<BlockPos, String>  sendQueueBuildingPacket        = (pos, defId)     -> {};
    public static BiConsumer<BlockPos, Integer> sendRemoveQueuedBuildingPacket = (pos, index)     -> {};
    public static BiConsumer<BlockPos, Long>    sendUpgradeBuildingPacket      = (pos, worldPos)  -> {};
    public static BiConsumer<BlockPos, Long>    sendRepairBuildingPacket       = (pos, worldPos)  -> {};
    public static BiConsumer<BlockPos, String>  sendSelectEraPathPacket        = (pos, pathId)    -> {};
    public static Consumer<BlockPos>            sendDepositPacket              = pos              -> {};
    public static BiConsumer<BlockPos, String>  sendContributeQuestPacket      = (pos, questId)   -> {};
    public static BiConsumer<BlockPos, String>  sendVerifyClearancePacket      = (pos, questId)   -> {};
    public static Consumer<BlockPos>            sendRequestStockPacket         = pos              -> {};
    // Carries requested items for BUY mode: List<(itemId, count)> encoded via C2SBuyPacket
    public static BiConsumer<BlockPos, List<C2SBuyPacket.Entry>> sendBuyPacket = (pos, items) -> {};
    // C2S: client requests raw NBT for a structure path (datapack support)
    public static BiConsumer<BlockPos, String> sendRequestNbtPacket = (pos, path) -> {};
    // S2C: server pushes raw NBT to a specific player
    public static BiConsumer<ServerPlayer, CompoundTag> sendNbtStructurePacket = (player, data) -> {};

    public static void pushStockToWatchers(ServerLevel level, Town town, BlockPos anchorPos) {
        pushToWatchers(level, town, anchorPos, t -> t.getStockUpdateData(anchorPos), sendStockUpdatePacket);
    }

    public static void pushBuildingListToWatchers(ServerLevel level, Town town, BlockPos anchorPos) {
        pushToWatchers(level, town, anchorPos, t -> t.getBuildingListData(anchorPos), sendBuildingListPacket);
    }

    public static void pushQuestUpdateToWatchers(ServerLevel level, Town town, BlockPos anchorPos) {
        pushToWatchers(level, town, anchorPos, t -> t.getQuestUpdateData(anchorPos), sendQuestUpdatePacket);
    }

    public static void pushEraUpdateToWatchers(ServerLevel level, Town town, BlockPos anchorPos) {
        pushToWatchers(level, town, anchorPos, t -> t.getEraUpdateData(anchorPos), sendEraUpdatePacket);
    }

    public static void pushCitizenUpdateToWatchers(ServerLevel level, Town town, BlockPos anchorPos) {
        pushToWatchers(level, town, anchorPos, t -> t.getCitizenUpdateData(anchorPos, level), sendCitizenUpdatePacket);
    }

    private static void pushToWatchers(ServerLevel level, Town town, BlockPos anchorPos,
                                       Function<Town, CompoundTag> dataFn,
                                       BiConsumer<ServerPlayer, CompoundTag> sender) {
        if (anchorPos == null) return;
        List<ServerPlayer> watchers = getWatchers(level, anchorPos);
        if (watchers.isEmpty()) return;
        CompoundTag data = dataFn.apply(town);
        for (ServerPlayer w : watchers) sender.accept(w, data);
    }

    // Sends a log entry to every player watching this town's hub, and sends a
    // chat message to subscribed players who do not currently have the hub open.
    public static void pushLogEntryToWatchers(ServerLevel level, Town town, BlockPos anchorPos, TownLogEntry entry) {
        if (anchorPos == null) return;
        List<ServerPlayer> watchers = getWatchers(level, anchorPos);
        if (!watchers.isEmpty()) {
            CompoundTag data = new CompoundTag();
            data.putLong("AnchorPos", anchorPos.asLong());
            data.putString("Type", entry.type().name());
            data.putString("Param", entry.param());
            data.putLong("Tick", entry.gameTick());
            for (ServerPlayer w : watchers) sendLogEntryPacket.accept(w, data);
        }

        Set<UUID> subscribers = town.getChatSubscribers();
        if (!subscribers.isEmpty()) {
            Component chatMsg = entry.toComponent();
            for (ServerPlayer player : level.players()) {
                if (subscribers.contains(player.getUUID())) {
                    player.sendSystemMessage(chatMsg);
                }
            }
        }
    }

    private static List<ServerPlayer> getWatchers(ServerLevel level, BlockPos anchorPos) {
        return level.players().stream()
            .filter(p -> p.containerMenu instanceof TownHubMenu m && anchorPos.equals(m.getAnchorPos()))
            .toList();
    }

}
