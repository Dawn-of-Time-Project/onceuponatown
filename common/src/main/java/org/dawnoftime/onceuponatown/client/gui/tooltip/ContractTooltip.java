package org.dawnoftime.onceuponatown.client.gui.tooltip;

import net.minecraft.world.inventory.tooltip.TooltipComponent;
import org.dawnoftime.onceuponatown.town.ContractEntry;

import java.util.List;

public record ContractTooltip(List<ContractEntry> entries) implements TooltipComponent {}
