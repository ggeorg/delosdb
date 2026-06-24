# S18 fix — MvccPageFileTest allocation contract

This cleanup overlay fixes the S18 test migration from the retired `MvccPageFile` API to the storage I/O `DelosPageVolume` API.

`DelosPageVolume.allocatePage` requires an explicit page type. The compatibility test now passes `DelosPage.DATA_PAGE_TYPE` at each allocation site.

No production code changed.
