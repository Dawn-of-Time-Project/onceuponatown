package org.dawnoftime.onceuponatown.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.dawnoftime.onceuponatown.Ouat;
import org.dawnoftime.onceuponatown.blockentity.TownAnchorBlockEntity;
import org.dawnoftime.onceuponatown.town.LevelTowns;
import org.dawnoftime.onceuponatown.town.Town;

public record C2SRepairBuildingPacket(BlockPos anchorPos, long buildingWorldPos) {
    public static final ResourceLocation ID = Ouat.modResource("c2s_repair_building");

    public static C2SRepairBuildingPacket decode(FriendlyByteBuf buf) {
        return new C2SRepairBuildingPacket(buf.readBlockPos(), buf.readLong());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(anchorPos);
        buf.writeLong(buildingWorldPos);
    }

    public static class Handler {
        public static void handle(C2SRepairBuildingPacket packet, ServerPlayer player) {
            ServerLevel level = (ServerLevel) player.level();
            if (!(level.getBlockEntity(packet.anchorPos()) instanceof TownAnchorBlockEntity)) return;
            Town town = LevelTowns.get(level).getTownAt(packet.anchorPos()).orElse(null);
            if (town == null) return;
            boolean queued = town.tryQueueRepair(BlockPos.of(packet.buildingWorldPos()), level);
            if (queued) {
                LevelTowns.get(level).markDirty();
                NetworkHelper.pushBuildingListToWatchers(level, town, packet.anchorPos());
            }
        }
    }
}
