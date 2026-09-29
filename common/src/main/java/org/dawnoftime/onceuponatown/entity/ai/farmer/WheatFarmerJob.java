package org.dawnoftime.onceuponatown.entity.ai.farmer;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.dawnoftime.onceuponatown.datapack.FarmerConfigDataHandler;
import org.dawnoftime.onceuponatown.entity.Npc;

import java.util.List;

public class WheatFarmerJob extends AbstractFarmerJob {

    public WheatFarmerJob(Npc npc) {
        super(npc);
    }

    @Override
    public String getJobId() { return "wheat_farmer"; }

    @Override
    public List<String> getProductionBuildings() {
        FarmerConfigDataHandler.Config cfg = getConfig();
        return cfg == null ? List.of() : cfg.productionBuildings;
    }

    @Override
    protected FarmerConfigDataHandler.Config getConfig() {
        return FarmerConfigDataHandler.get("wheat_farmer");
    }

    @Override
    protected Block getCropBlock() {
        return Blocks.WHEAT;
    }

    @Override
    protected Item getSeedItem() {
        return Items.WHEAT_SEEDS;
    }
}
