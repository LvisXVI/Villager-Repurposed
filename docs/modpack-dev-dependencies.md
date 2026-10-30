# Modpack Dev Dependencies (Minecraft 26.2)

Gradle pulls these via the [Modrinth Maven](https://docs.modrinth.com/docs/other-ways-to-access/modrinth-maven/) (`maven.modrinth:*`).

| Master-prompt component | Modrinth project | 26.2 dev dependency | Notes |
|---|---|---|---|
| ChoiceTheorem's Overhauled Village | `ct-overhaul-village` | **4.1.0** (`Vb09nbge`) | Requires **Lithostitched** + **Cloth Config** |
| Lithostitched | `lithostitched` | **1.8.0-fabric-26.2** | CTOV structure injection |
| Cloth Config | `cloth-config` | **26.2.155+fabric** | CTOV / UI dependency |
| Farmer's Delight Refabricated | `farmers-delight-refabricated` | **26.2-3.6.30** | |
| Chef's Delight | `chefs-delight` | **1.0.6-fabric-26.2** | |
| More Villagers: Re-employed | `more-villagers-re-employed` | **1.26.7.1** | Newer builds target 26.3+ snapshots only |
| EMF (Fresh Animations stack) | `entity-model-features` | **3.3.12-fabric-26.2** | |
| ETF (Fresh Animations stack) | `entitytexturefeatures` | **7.2.6-fabric-26.2** | |
| Serene Seasons | `serene-seasons` | **26.1.2.0.6** | |
| Towns and Towers | `towns-and-towers` | **1.13.11** | |
| Village Taverns | `village-taverns` | **1.3.0+26.2-fabric** | Requires **structure-pool-api** |
| Villager API (More Villagers) | `villagerapi` | **1.26.7.1** | Transitive for More Villagers |
| GlitchCore (Serene Seasons) | `glitchcore` | **26.2.0.0.0** | Transitive for Serene Seasons |
| Cristel Lib (Towns and Towers) | `cristel-lib` | **fabric-26.2-3.1.13** | Transitive for Towns and Towers |
| Structure Pool API | `structure-pool-api` | **1.3.0+26.2-fabric** | Transitive for Village Taverns |
| **Fresh Animations** | — | *not a Fabric mod* | Resource/animation pack; use with EMF/ETF, optional under `run/resourcepacks/` |
| **Actually 3D Stuff** | — | *no 26.2 release on Modrinth* | Not wired in Gradle; add manually if a matching artifact appears |

Headless CTOV world: `VP_AUTOTEST=ctov` with `level-name=vp-ctov-headless`, normal terrain, `generate-structures=true`, seed `8675309` (see `scripts/run-ctov-headless.sh`).
