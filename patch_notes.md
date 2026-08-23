## 🏘️ Once Upon a Town v0.0.17 | New villagers, new features, more buildings !
### New buildings & reworked structures:
- Added a building cow farm for the ranching orientation (with 6 levels)
- Added a building sheep farm for the ranching orientation (with 6 levels)
- Updated the pig farm to align on the new ranching farms
- Added back the house 1 with a refreshed design
- Updated a ton of building levels to align the upgrades among the new buildings and make the appearance of the buildings evolve over time more coherent
### New jobs and features:
- Added a new category of jobs, the breeders. In a building JSON file, you can customize what's the max herds a building can contains, this cap is going to be the one the breeders will follow to make the herds reproduce over time
- New job cowherd that lives in the cow farm. It will breed the animals and produce leather
- New job swineherd that lives in the pig farm. It will breed the animals and produce cooked porkchop
- Updated the shepard to breed the sheep and grow the sheep population over time, it is now tied to the sheep farm
### New features:
- Added a system in the settlement summarize widget to check how many workers you have and to locate them with a ping
- Added a repair button to fix damaged builds. You can use it in the map widget, bottom right corner
- Added a 'i' icon in the building catalog interface to give a short summarize about what is the structure and which purpose it serves
- Added a system to preview the max stock of the village, it can be enabled or disabled. It helps to scope the amount of resources you can supply the village if you want to trade resources or afk and collect back
- Added various tooltips to help the player to read and understand the interface
### Internal improvements:
- Reworking the datapack of the plains culture to allow the player to specifically locate a type of orientation : industrial, agricultural, pastoral. It should make the process of finding villages less random
- Added a label to the structures in the building catalog to understand what represent each color
- Improved the upgrade bars display to avoid these going out of the frame
- Fixed the issue with NPC being stuck in doors
- Made some internal changes to allow more datapack freedom and custom cultures. The builder is able to expend and build custom cultures now
- Made some improvements to the NPC navigation system to avoid him climbing and walls and being stuck on roofs
- Made a ton of internal code changes to improve the code performances and readability 