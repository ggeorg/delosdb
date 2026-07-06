# DelosDB Storage Roadmap

This roadmap replaces the older checkpoint-cycle plan after the storage robustness and cleanup phases closed.

## North star

```text
Preserve Derby compatibility.
Do not preserve Derby internals for their own sake.
```

DelosDB remains a Derby-compatible database engine, not a Derby-identical codebase. The public and durable compatibility boundaries stay protected while DelosDB-owned internals can evolve behind explicit, tested seams.

## Architecture model

```text
Derby-compatible SQL / JDBC / DRDA / catalog behavior
  -> DelosDB storage access boundary
  -> storage modes
       1. Derby-compatible heap mode
       2. delos_mvcc modern MVCC mode
       3. future modern heap/table mode if justified
  -> shared DelosDB storage services
       diagnostics
       consistency checking
       storage inspector
       allocation/free-space helpers
       page cache / mutation discipline
       recovery/checkpoint helpers
       storage statistics
```

## Completed checkpoint cycles

The older roadmap used this rule:

```text
one bounded MVCC modernization slice
one inherited/heap compatibility modernization slice
one shared diagnostic/static/consistency gate
repeat
```

The current codebase has now completed the planned near-term and mid-term checkpoints from that roadmap:

```text
Ordered MVCC equality lookup                         closed
Ordered MVCC range scan                              closed
Ordered MVCC index authority checkpoint              closed
Derby heap sanity checker                            closed
Heap object deserialization hardening                closed
Heap/raw-store cleanup gates                         closed
Cross-engine storage consistency framework           closed
Candidate-index quarantine                           closed
Candidate-index authority removal                    closed
Heap diagnostics expansion                           closed
Shared storage inspector consolidation               closed
Pinned/dirty MVCC page cache                         closed
Attribute-level MVCC overflow storage                closed
MVCC subsystem recovery records                      closed
Heap internal cleanup phase 1                        closed
Cleanup/consolidation phase                          closed
Derby module parity preservation                     closed
```

These are guarded by focused SQL tests, provider/runtime checks, and S0 static gates. Do not restart these phases unless a regression report shows a specific gap.

## Current phase: fork-diff classification

The next gap is not another feature slice. It is governance around the inherited Derby code that DelosDB intentionally changed.

DelosDB now has hundreds of modified inherited Java files. Most are harmless compatibility-preserving changes, but a small set of inherited files form high-risk seams:

```text
SQL grammar and CREATE TABLE ... USING provider support
catalog storage-provider metadata
optimizer/executor storage-provider hooks
access-method bridge and heap checker wiring
object deserialization hardening
DRDA/server modernization seams
```

The current phase therefore classifies high-risk Derby fork diffs and makes them visible to S0 closeout. Future changes to these inherited files should be explained as one of:

```text
COMPATIBILITY_PRESERVING
EXTENSION_SEAM
STORAGE_SPLIT
HARDENING
INTENTIONAL_REPLACEMENT
```

This prevents silent drift while preserving the strategic rule that Derby internals may evolve when compatibility is protected.

## Next execution order

After fork-diff classification is green, the next phase should be chosen from current reports, not from the now-closed older list:

```text
1. Review the fork-diff classification report.
2. Tighten or split classifications only where a real inherited-code risk is found.
3. Use the next source/report comparison to choose one bounded correctness slice.
4. Avoid broad module merging; Derby module parity remains the default.
5. Avoid repeating already-closed heap/MVCC/storage-inspector milestones.
```

## Decision rules

Work on MVCC when a normal SQL authority still depends on temporary or diagnostic structures.

Work on heap/inherited code when the default Derby-compatible path lacks diagnostics, hardening, or clear compatibility gates.

Work on shared services when heap and MVCC already have comparable proof points and a common diagnostic/inspection/report shape prevents duplication.

Work on fork governance when inherited Derby files become high-risk extension seams and need explicit classification before deeper edits.
