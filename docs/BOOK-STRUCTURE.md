
# DelosDB Internals Book Structure

## Working title

**DelosDB Internals: From SQL Text to Durable State**

## Release commitment

The DelosDB v1.0 book is a source-accurate systems textbook built from the production engine. It is
written continuously during Phases 8-11 and release-verified in Phase 12.

The final v1 structure contains seven parts and 33 chapters:

```text
Part I    Product, Lineage, and Whole-System Architecture       Chapters 1-5
Part II   SQL Compilation                                       Chapters 6-10
Part III  Execution and Explanation                             Chapters 11-15
Part IV   Database and Transaction Ownership                    Chapters 16-19
Part V    Physical Storage                                      Chapters 20-25
Part VI   Durability and Operations                             Chapters 26-30
Part VII  Evidence, Teaching, and Research                      Chapters 31-33
```

## Editorial rule

A new implementation feature does not automatically create a chapter. It must fit the chapter that
owns its invariant, or replace/merge an existing chapter through an explicit editorial decision.

Every technical chapter includes:

```text
product and compatibility contract
mental model
ownership and source map
algorithm, state, and invariants
one end-to-end production trace
failure, security, and recovery behavior
structured evidence and executable proof
research boundary and exercises
```

## Authoritative local sources

```text
.delosdb-v1/04-book/00-BOOK-VISION.md
.delosdb-v1/04-book/01-TABLE-OF-CONTENTS.md
.delosdb-v1/04-book/02-CHAPTER-CONTRACT.md
.delosdb-v1/04-book/03-LABS-AND-EXERCISES.md
.delosdb-v1/04-book/04-BOOK-PRODUCTION-MAP.md
```
