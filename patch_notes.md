## 🏘️ Once Upon a Town v0.0.19 | Player controlled villages, new NPC and buildings, code optimizations
### Player-Controlled Villages
- Fully implemented the player-controlled village system. A player can now create and own its own village.
- To do so, you will have to merge one Minecraft banner and one Recognition Medal to obtain a Controlled Village Banner.
- Placing the banner consumes it (shift+right click in hand on a block), it creates the associated town center, and unlocks all signature builds and era builds from the recognition medal.
- In a player-controlled village, you can freely take back the resources produced by the village or store ones. Buildings are no longer placed autonomously.
### Recognition Medals
- The recognition system now stores more information: the main orientation, the building unlocked from the era, and the building from the medal in the stock (if there is one).
- Medals are now incremental: they become stronger each time you take one back and build another village with it.
- Signature buildings (tied to the orientation) are only unlocked in the medal once there are reaching there final level
### Gameplay changes
- With all the new buildings in this update, I decided to spread the unique buildings between the branches and add more each village path more impactful and interesting to explore to unlock key buildings through the medal
- The market is not tied to the urban orientation, the forge got renamed craftsman and receive the carpenter and the toolsmith, the rural receives the barn and the granary becomes an era gated building, not a signature one
- The mine and the open mine can now produce iron, making these buildings way more powerful and useful for your journey. The toolsmith can smelt and transform these resources into tools
- The granary is the first era gated building that request a signature building to be placed, more buildings will be like this one, making the medal gameplay more powerful and central
- The market is now stronger, the amount of trading slot and discount price bonuses are extended to all the npc in the village
### New Buildings
- Added the Potato Farm (6 levels).
- Added the Wheat Farm (6 levels).
- Added the Barn (6 levels). It helps extend the village capacity.
- Added the Toolsmith (6 levels) for the Craftsman branch.
### NPCs
- Added new NPCs: Potato Farmers, Wheat Farmers and Toolsmith. They work on their associated building and tend the fields or produce basic tools and utility items.
- Heavily improved the navigation system for every NPC. They now navigate to their building's job entrance before performing their jobs, which reduces the time they remain stuck or use strange pathfinding.
- Reworked the trading system. Now every npc exposes a trading interface when they are making side activities
- Updated some NPC clothes to add more variety between them.
- Entirely new NPC management system that reduces code bloat and ticking issues, and aligns the code between NPC types.
### Interface
- Added a button to enable or disable the auto-upgrade system, for both villages and player villages.
- Added a red indicator showing which building is being worked on, instead of having to look at the map.
### Fixes and Optimizations
- Updated the Mine NBT, it should now be built properly.
- Updated a bunch of building NBT where the builder ended up stuck in the upgrade process
- Many internal optimizations regarding code bloat and ticking issues.
- A lot of qol changes on the datapack/json configurations for future custom cultures. I reduced the amount of fields to fill, the amount of duplicated values and renamed some fields to sounds less confusing
