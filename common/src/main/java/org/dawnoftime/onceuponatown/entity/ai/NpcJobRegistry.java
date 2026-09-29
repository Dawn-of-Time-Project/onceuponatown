package org.dawnoftime.onceuponatown.entity.ai;

import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.entity.ai.beekeeper.BeekeeperJob;
import org.dawnoftime.onceuponatown.entity.ai.builder.BuilderJob;
import org.dawnoftime.onceuponatown.entity.ai.farmer.PotatoFarmerJob;
import org.dawnoftime.onceuponatown.entity.ai.farmer.WheatFarmerJob;
import org.dawnoftime.onceuponatown.entity.ai.herder.BreederJob;
import org.dawnoftime.onceuponatown.entity.ai.lumberjack.LumberjackJob;
import org.dawnoftime.onceuponatown.entity.ai.merchant.MerchantJob;
import org.dawnoftime.onceuponatown.entity.ai.miner.MinerJob;
import org.dawnoftime.onceuponatown.entity.ai.toolsmith.ToolsmithJob;

public class NpcJobRegistry {
    public static final java.util.List<String> ALL_JOB_IDS =
        java.util.List.of("beekeeper", "builder", "cowherd", "lumberjack", "merchant", "miner", "potato_farmer", "shepherd", "swineherd", "toolsmith", "wheat_farmer");

    public static NpcJob create(String jobId, Npc npc) {
        return switch (jobId) {
            case "beekeeper"     -> new BeekeeperJob(npc);
            case "builder"       -> new BuilderJob(npc);
            case "cowherd"       -> new BreederJob(npc, "cowherd");
            case "lumberjack"    -> new LumberjackJob(npc);
            case "merchant"      -> new MerchantJob(npc);
            case "miner"         -> new MinerJob(npc);
            case "potato_farmer" -> new PotatoFarmerJob(npc);
            case "shepherd"      -> new BreederJob(npc, "shepherd");
            case "swineherd"     -> new BreederJob(npc, "swineherd");
            case "toolsmith"     -> new ToolsmithJob(npc);
            case "wheat_farmer"  -> new WheatFarmerJob(npc);
            default -> throw new IllegalArgumentException("Unknown job: " + jobId);
        };
    }
}
