package org.dawnoftime.onceuponatown.network;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import org.dawnoftime.onceuponatown.Ouat;
import org.dawnoftime.onceuponatown.screen.TownHubMenu;

import java.io.InputStream;
import java.util.Optional;

public record C2SRequestNbtPacket(BlockPos anchorPos, String nbtPath) {
    public static final ResourceLocation ID = Ouat.modResource("c2s_request_nbt");

    public static C2SRequestNbtPacket decode(FriendlyByteBuf buf) {
        return new C2SRequestNbtPacket(buf.readBlockPos(), buf.readUtf());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(anchorPos);
        buf.writeUtf(nbtPath);
    }

    public static class Handler {
        public static void handle(C2SRequestNbtPacket packet, ServerPlayer player) {
            if (!(player.containerMenu instanceof TownHubMenu menu && packet.anchorPos().equals(menu.getAnchorPos()))) return;
            ResourceLocation defLoc;
            try {
                defLoc = new ResourceLocation(packet.nbtPath());
            } catch (Exception e) {
                return;
            }
            ResourceLocation loc = new ResourceLocation(defLoc.getNamespace(), "structures/" + defLoc.getPath() + ".nbt");
            Optional<Resource> res = player.getServer().getResourceManager().getResource(loc);
            if (res.isEmpty()) return;
            try (InputStream stream = res.get().open()) {
                byte[] compressedBytes = stream.readAllBytes();
                CompoundTag data = new CompoundTag();
                data.putString("Path", packet.nbtPath());
                data.putByteArray("Nbt", compressedBytes);
                NetworkHelper.sendNbtStructurePacket.accept(player, data);
            } catch (Exception e) {
                // Structure file could not be read; client stays on "..."
            }
        }
    }
}
