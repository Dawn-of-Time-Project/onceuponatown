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

public record C2SToggleAutoUpgradePacket(BlockPos anchorPos) {
    public static final ResourceLocation ID = Ouat.modResource("c2s_toggle_auto_upgrade");

    public static C2SToggleAutoUpgradePacket decode(FriendlyByteBuf buf) {
        return new C2SToggleAutoUpgradePacket(buf.readBlockPos());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(anchorPos);
    }

    public static class Handler {
        public static void handle(C2SToggleAutoUpgradePacket packet, ServerPlayer player) {
            ServerLevel level = (ServerLevel) player.level();
            if (!(level.getBlockEntity(packet.anchorPos()) instanceof TownAnchorBlockEntity)) return;
            Town town = LevelTowns.get(level).getTownAt(packet.anchorPos()).orElse(null);
            if (town == null) return;
            town.setAutoUpgradeEnabled(!town.isAutoUpgradeEnabled());
            LevelTowns.get(level).markDirty();
            NetworkHelper.pushEraUpdateToWatchers(level, town, packet.anchorPos());
        }
    }
}
