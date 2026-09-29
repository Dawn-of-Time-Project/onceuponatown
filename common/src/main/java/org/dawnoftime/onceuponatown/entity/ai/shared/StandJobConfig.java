package org.dawnoftime.onceuponatown.entity.ai.shared;

import org.dawnoftime.onceuponatown.entity.ai.ActivityDef;
import org.jetbrains.annotations.Nullable;

import java.util.List;

// Combined config interface for jobs that use a stand-based work pattern.
public interface StandJobConfig extends SleepConfig {
    @Nullable String getWorkStandType();
    List<ActivityDef> getSecondaryActivities();
    List<String> getWorkBuildings();
}
