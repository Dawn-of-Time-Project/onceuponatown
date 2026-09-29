package org.dawnoftime.onceuponatown.town;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class StandSlot {
    public final BlockPos position;
    public final String type;
    public @Nullable UUID occupant;
    public final @Nullable String toolItem;
    public final @Nullable BlockPos associatedBlockPos;

    public StandSlot(BlockPos position, String type, @Nullable UUID occupant, @Nullable String toolItem, @Nullable BlockPos associatedBlockPos) {
        this.position = position;
        this.type = type;
        this.occupant = occupant;
        this.toolItem = toolItem;
        this.associatedBlockPos = associatedBlockPos;
    }
}
