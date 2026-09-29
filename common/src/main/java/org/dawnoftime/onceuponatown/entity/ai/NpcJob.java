package org.dawnoftime.onceuponatown.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.town.LevelTowns;
import org.dawnoftime.onceuponatown.town.Town;

public interface NpcJob {
    void tick();
    String getJobId();

    default InteractionResult onPlayerInteract(Player player) { return InteractionResult.PASS; }

    default Town findTown(ServerLevel level, Npc npc) {
        BlockPos anchor = npc.getTownAnchorPos();
        if (anchor == null) return null;
        return LevelTowns.get(level).getTownAt(anchor)
            .filter(t -> t.getNpcsByJob(getJobId()).contains(npc.getUUID()))
            .orElse(null);
    }
}
