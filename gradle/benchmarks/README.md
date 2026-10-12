# Benchmark build ownership

These scripts register opt-in benchmark families for the owning test project. Runtime artifacts and benchmark classes are supplied explicitly; registration must not resolve external engine configurations or launch processes.

`rawstore-occupancy.gradle` owns `runDelosF08OccupancyCampaign`. Its registration closure takes the benchmark-class provider, Delos runtime jars and upstream Derby configuration. Property parsing and external resolution remain at task execution, preserving the existing preflight and measurement contracts.

Further benchmark families should be moved by responsibility after their helper/configuration dependencies are classified. Keep stable task names and workload/report semantics. Active performance evidence and older diagnostic variants must remain reproducible during extraction; deleting a task because its name looks historical is not a classification.
