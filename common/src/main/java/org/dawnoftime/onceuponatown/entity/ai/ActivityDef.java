package org.dawnoftime.onceuponatown.entity.ai;

public record ActivityDef(
    String requiredBuilding,
    String heldItem,
    AnimationType animationType,
    String targetBlock,   // null = walk to BB center; mutually exclusive with standType
    String standType,     // null for non-SELL activities; non-null routes NPC to a market stand
    double workReach      // radius in blocks within which the NPC is considered arrived
) {}
