package org.dawnoftime.onceuponatown.entity.ai;

import java.util.List;

public record ActivityDef(
    String requiredBuilding,
    String heldItem,
    AnimationType animationType,
    String targetBlock,              // null = walk to BB center instead of scanning for a block
    List<String> productionBuildings // SELL only: building defIds whose production defines the tradeable items
) {}
