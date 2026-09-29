package org.dawnoftime.onceuponatown.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.dawnoftime.onceuponatown.Ouat;
import org.dawnoftime.onceuponatown.blockentity.TownAnchorBlockEntity;
import org.dawnoftime.onceuponatown.item.RecognitionMedalItem;
import org.dawnoftime.onceuponatown.registry.ItemRegistry;
import org.dawnoftime.onceuponatown.screen.TownHubMenu;
import org.dawnoftime.onceuponatown.town.LevelTowns;
import org.dawnoftime.onceuponatown.town.Town;

public record C2SClaimMedalPacket(BlockPos anchorPos) {
    public static final ResourceLocation ID = Ouat.modResource("c2s_claim_medal");

    public static C2SClaimMedalPacket decode(FriendlyByteBuf buf) {
        return new C2SClaimMedalPacket(buf.readBlockPos());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(anchorPos);
    }

    public static class Handler {
        public static void handle(C2SClaimMedalPacket packet, ServerPlayer player) {
            ServerLevel level = (ServerLevel) player.level();
            if (!(level.getBlockEntity(packet.anchorPos()) instanceof TownAnchorBlockEntity)) return;
            if (!(player.containerMenu instanceof TownHubMenu)) return;

            Town town = LevelTowns.get(level).getTownAt(packet.anchorPos()).orElse(null);
            if (town == null) return;
            if (town.isMedalClaimed()) return;

            Town.MedalSnapshot snapshot = town.claimMedal();
            if (snapshot.signatureIds().isEmpty() && snapshot.eraUnlockedIds().isEmpty()) return;

            ItemStack medal = new ItemStack(ItemRegistry.RECOGNITION_MEDAL);
            RecognitionMedalItem.write(medal, snapshot);
            if (!player.getInventory().add(medal)) player.drop(medal, false);

            LevelTowns.get(level).markDirty();
            NetworkHelper.sendTownHubPacket.accept(player, town.getHubData(packet.anchorPos(), level));
        }
    }
}
