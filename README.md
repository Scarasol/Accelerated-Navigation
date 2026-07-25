# Accelerated Navigation

Accelerated Navigation is the server-side navigation infrastructure split out
of Fungal Hazard. It is an independent Forge mod with no dependency on Fungal
Hazard or its surface-path node types.

## Stable boundaries

- The macro planner owns only low-cost component topology and complete
  structural corridors. Exact boundary masks are retained for later refinement.
- Fine planners remain replaceable backends. A backend that implements the
  resumable API receives strict time slices; an unmodified backend can only be
  scheduled as an atomic soft-budget task.
- Entity types listed by exact registry ID in `bypassEntityIds` retain their
  original navigation without macro planning or scheduler interception.
- The scheduler owns request lifecycle, priority, fairness, queue pressure and
  server-tick budget. It does not know Minecraft `Path`, Fungal surface nodes,
  collision geometry or movement execution.
- Synchronous reachability queries keep their original final-result contract.
  Incomplete A* frontier paths are not exposed as executable paths.

## Current split

The module now contains the neutral resumable-search contract, strict and soft
scheduling channels, weighted deficit round-robin fairness, cross-dimension
rotation, exception isolation and exact-ID bypass policy.

The base and second macro-topology layers are implemented:

- Immutable 16 x 16 x 16 section topology with ground and volume components,
  exact boundary masks, fluid facts and exact-check markers.
- Same-height ground components plus profile-filtered step, drop and short-jump
  requirements; volume navigation uses six-neighbor components.
- Pure resumable Weighted A* using Minecraft's `BinaryHeap` and `Node`. Macro
  search performs no concrete `Navigation` calls and never restarts for
  per-edge certification.
- Lazy in-memory 32 x 32 x 32 super clusters contract the directed graph of
  eight base clusters with profile-aware strongly connected components. They
  neither rescan blocks nor build all-pairs entrance edges. Profile-filtered
  component labels are prepared on their outer faces so cross-cluster queries
  use fixed boundary scans instead of component Cartesian products.
- Long queries select a super-cluster corridor and then run a time-sliced base
  refinement constrained to that corridor. Only the resulting base-component
  corridor is returned.
- Main-thread section snapshots, one low-priority topology worker, immutable
  publication and revision checks.
- Sidecar persistence through Minecraft `RegionFile`, bounded decoding and
  corruption fallback.
- Block-change invalidation, boundary-neighbor invalidation and chunk-unload
  eviction without forcing chunk loads.
- Lazy query-graph indexing, so short searches do not scan unrelated cached
  sections.
- Isolated JUnit and GameTest coverage, including short/medium/long timing and
  cluster-build resource reports.

## Terrain benchmarks

`./gradlew runTerrainBenchmarkServer` starts a
test-only dedicated server with the fixed seed `73939133` and the normal world
preset. It loads real Overworld surface, underground cave and Nether chunks,
then records section snapshot/build cost and 8, 96 and 512 block route queries
in `build/reports/real-terrain-topology.json`. Chunk generation/load, macro
search and a single first-window vanilla refinement probe are timed separately.
Before any measured query, the test-only harness indexes directed strongly
connected components in the published base graph and chooses endpoints from one
component. This preselection is excluded from macro timing and proves only base
structural reachability; it is not reported as complete physical execution.
Super-cluster worker build, high-level expansion and constrained base refinement
are reported separately. Build-worker and persistence-worker queue waits,
promotions and cancellations are also reported separately. The macro timing
always reports zero concrete Navigation calls. Missing loaded
topology, unavailable chunks, structural disconnection and timeout are distinct
outcomes rather than being collapsed into unreachable.

`./gradlew test` still writes
`build/reports/macro-topology-synthetic-microbenchmark.json`. That report is a
deterministic graph-scaling microbenchmark only. It contains no generated
terrain and must not be used as evidence of real-world route performance.

The previous experimental global `PathNavigation.createPath` redirection was
not migrated because it returned incomplete paths from a synchronous API and
held live vanilla evaluators while entities moved. Vanilla and third-party
fine-navigation adapters remain a later phase and must preserve their original
synchronous reachability contracts.
