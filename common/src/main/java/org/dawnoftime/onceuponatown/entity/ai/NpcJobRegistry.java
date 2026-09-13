package org.dawnoftime.onceuponatown.entity.ai;

import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.entity.ai.beekeeper.BeekeeperJob;
import org.dawnoftime.onceuponatown.entity.ai.builder.BuilderJob;
import org.dawnoftime.onceuponatown.entity.ai.cowherd.CowHerdJob;
import org.dawnoftime.onceuponatown.entity.ai.lumberjack.LumberjackJob;
import org.dawnoftime.onceuponatown.entity.ai.merchant.MerchantJob;
import org.dawnoftime.onceuponatown.entity.ai.miner.MinerJob;
import org.dawnoftime.onceuponatown.entity.ai.shepherd.ShepherdJob;
import org.dawnoftime.onceuponatown.entity.ai.swineherd.SwineherdJob;

public class NpcJobRegistry {
    public static final java.util.List<String> ALL_JOB_IDS =
        java.util.List.of("beekeeper", "builder", "cowherd", "lumberjack", "merchant", "miner", "shepherd", "swineherd");

    public static NpcJob create(String jobId, Npc npc) {
        return switch (jobId) {
            case "beekeeper"  -> new BeekeeperJob(npc);
            case "builder"    -> new BuilderJob(npc);
            case "cowherd"    -> new CowHerdJob(npc);
            case "lumberjack" -> new LumberjackJob(npc);
            case "merchant"   -> new MerchantJob(npc);
            case "miner"      -> new MinerJob(npc);
            case "shepherd"   -> new ShepherdJob(npc);
            case "swineherd"  -> new SwineherdJob(npc);
            default -> throw new IllegalArgumentException("Unknown job: " + jobId);
        };
    }
}
