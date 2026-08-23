package org.dawnoftime.onceuponatown.network;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.dawnoftime.onceuponatown.Ouat;
import org.dawnoftime.onceuponatown.client.gui.widgets.NbtPreviewWidget;

public record S2CNbtStructurePacket(String nbtPath, byte[] compressedBytes) {
    public static final ResourceLocation ID = Ouat.modResource("s2c_nbt_structure");

    public static S2CNbtStructurePacket fromData(CompoundTag data) {
        return new S2CNbtStructurePacket(data.getString("Path"), data.getByteArray("Nbt"));
    }

    public static S2CNbtStructurePacket decode(FriendlyByteBuf buf) {
        return new S2CNbtStructurePacket(buf.readUtf(), buf.readByteArray());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(nbtPath);
        buf.writeByteArray(compressedBytes);
    }

    public static class Handler {
        public static void handle(S2CNbtStructurePacket packet) {
            Minecraft.getInstance().execute(() ->
                NbtPreviewWidget.receiveStructure(packet.nbtPath(), packet.compressedBytes()));
        }
    }
}
