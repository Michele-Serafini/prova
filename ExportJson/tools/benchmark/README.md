# Reproducible JDI benchmark

`run.py` compares the pre-optimization Git commit `5b80db2` with the current
working tree. It compiles both implementations into temporary directories and
loads them separately while inspecting the same real JVM suspended at a JDI
breakpoint. It does not start IntelliJ or use mock runtime objects.

Requirements: Python 3, Git, JDK 21, Gson 2.13.1 and JetBrains annotations 26.0.2.
For `--spring`, also supply the runtime dependencies of Spring Context 6.2.6:
Spring AOP, Beans, Core, Context, Expression and JCL, plus Micrometer Observation
and Commons 1.14.5. The JARs can be taken from the Gradle dependency cache after
running the project tests. Only dependency JARs should be on this classpath.

Example (Linux/macOS):

```sh
python3 tools/benchmark/run.py --java-home /path/to/jdk-21 \
  --classpath '/path/to/dependency-jars/*' --spring
```

PowerShell:

```powershell
python tools/benchmark/run.py --java-home 'C:\path\to\jdk-21' `
  --classpath 'C:\path\to\dependency-jars\*' --spring
```

Use the platform classpath separator (`:` on Unix, `;` on Windows) when listing
individual JARs. Override the reference using `--baseline-ref COMMIT` if needed.
Both references must have the public scanner/lookup entry points used by the
harness. The reference commit must be available in local Git history.

The fixtures cover 500 objects with 12 integer fields, 20 objects with 96 fields,
a sparse HashMap with 16,384 buckets, a BigInteger with 1,024 magnitude words and,
optionally, Spring type/identity lookup with 500 ordinary singleton beans plus
one selected bean. The fixture creates its data before the breakpoint.

The harness checks exact JSON equality (including SHA-256) for scanner results
and exact selected-object identity for Spring results. Each case performs two
warmup scans, then reports the median of five measured scans. Implementations
alternate execution order to reduce scheduling bias. Startup, serialization of
the returned JSON, result comparison and debugger tracing are outside timing.

JDWP requests are counted in a separate warmed scan using JDI's `TRACE_SENDS`.
The trace is consumed in memory and only aggregate command names/counts are
printed. These are actual debugger protocol requests rather than calls on a
mock JDI interface. No getters or methods are invoked in the debuggee.

Timings are observations for these fixtures, JVM, machine and local debugger
transport. They are not latency guarantees for arbitrary objects or remote
debugging. Protocol-request reductions are a more stable comparison. The harness
requires permission to launch a JVM and listen on a local debugger socket.
