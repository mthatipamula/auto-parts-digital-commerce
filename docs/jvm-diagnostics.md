# JVM and Kubernetes diagnostics runbook

The catalog service ships a **JVM lab** (`/api/catalog/lab/*`, admin-only, on locally via `LAB_ENABLED=true`) with three
deliberate problems. Each section: reproduce, capture, read, fix.

Every image runs with these JVM settings (see `Dockerfile`):

| Flag | Why |
|---|---|
| `-XX:MaxRAMPercentage=70` | Heap sized from the **container** limit, leaving ~30% for metaspace, thread stacks, code cache, direct buffers. Fixed `-Xmx` + container limit is the classic OOMKilled cause. |
| `-XX:+UseG1GC -XX:MaxGCPauseMillis=100` | Balanced latency/throughput for 1-4 GB heaps. Consider ZGC (`-XX:+UseZGC -XX:+ZGenerational`) for sub-ms pauses on larger heaps. |
| `-Xlog:gc*,safepoint:file=/diagnostics/gc.log...` | Rotating GC log for post-mortems (pause times, promotion failures, humongous allocations). |
| `-XX:StartFlightRecording=...maxage=30m` | Always-on JFR (~1% overhead): the last 30 min of CPU samples, allocations, locks, GC, I/O. |
| `-XX:+HeapDumpOnOutOfMemoryError -XX:+ExitOnOutOfMemoryError` | Capture evidence, then exit so Kubernetes restarts a clean pod rather than leaving a half-dead JVM. |

The flags are set with `JDK_JAVA_OPTIONS`, not `JAVA_TOOL_OPTIONS`: the latter is read by **every** JVM, so running
`jcmd` inside the container would start a second flight recording and rotate the service's GC log.

Get an admin token first (local stack):

```bash
TOKEN=$(curl -s -d grant_type=password -d client_id=test-harness -d client_secret=$TEST_CLIENT_SECRET \
  -d username=admin -d password=$DEMO_ADMIN_PASSWORD \
  http://localhost:8180/realms/auto-parts/protocol/openid-connect/token | jq -r .access_token)
```

## 1. Memory leak: heap dump

```bash
for i in 1 2 3 4; do curl -s -X POST -H "Authorization: Bearer $TOKEN" "http://localhost:8081/api/catalog/lab/leak?mb=100"; done
docker compose exec catalog-service jcmd 1 GC.heap_info                      # heap climbing, old gen not reclaimed
docker compose exec catalog-service jcmd 1 GC.heap_dump /diagnostics/leak.hprof
docker compose cp catalog-service:/diagnostics/leak.hprof .
```

Open `leak.hprof` in **Eclipse MAT** → *Leak Suspects* / *Dominator Tree*: one `ArrayList` held by the static field
`JvmLab.LEAK` retains ~400 MB of `byte[]`. **Fix:** bound the collection (eviction/TTL, e.g. Caffeine with
`maximumSize`), or don't hold request data in static state. In production signs are: old-gen usage rising after each
full GC, GC frequency increasing, then OOMKilled or `OutOfMemoryError`.

## 2. CPU hot spot: flame graph

```bash
for i in $(seq 8); do curl -s -X POST -H "Authorization: Bearer $TOKEN" "http://localhost:8081/api/catalog/lab/cpu?ms=5000" & done
docker compose exec catalog-service jcmd 1 JFR.dump name=continuous filename=/diagnostics/cpu.jfr
docker compose cp catalog-service:/diagnostics/cpu.jfr .
jfr print --events jdk.ExecutionSample cpu.jfr | grep -A3 "stackTrace" | head    # or open in JDK Mission Control
```

In **JDK Mission Control** → *Method Profiling* (or async-profiler's flame graph: `asprof -d 30 -f cpu.html 1`),
the widest frame is `JvmLab.cpu` → `Math.sqrt`. Read flame graphs bottom-up: width = time on CPU; look for wide
plateaus near the top. **Fix:** remove the hot loop, cache the result, or move the work off the request path.

## 3. Lock contention: thread dump

```bash
for i in $(seq 10); do curl -s -X POST -H "Authorization: Bearer $TOKEN" "http://localhost:8081/api/catalog/lab/contention?ms=3000" & done
docker compose exec catalog-service jcmd 1 Thread.print -l > threads.txt
grep -B2 -A8 "BLOCKED" threads.txt | head -40
```

Many threads are `BLOCKED (on object monitor)` waiting to lock the same `JvmLab.LOCK`, one thread `TIMED_WAITING`
holding it. Take 3 dumps ~5 s apart: the same frames in all three = a real bottleneck, not a snapshot accident.
With virtual threads, `synchronized` around blocking I/O also **pins** the carrier thread; JFR's
`jdk.VirtualThreadPinned` event shows it (`jfr print --events jdk.VirtualThreadPinned`). **Fix:** shrink the critical
section, use `ReentrantLock`, or avoid shared mutable state.

## 4. Kubernetes runtime checklist

| Symptom | Look at | Typical cause / fix |
|---|---|---|
| `OOMKilled` (exit 137), no Java OOM | `kubectl describe pod`, `kubectl top pod` | Off-heap growth or heap % too high for the limit → lower `MaxRAMPercentage`, check direct buffers / thread count (`jcmd 1 VM.native_memory summary` with `-XX:NativeMemoryTracking=summary`) |
| CrashLoop on startup | `kubectl logs --previous`, startup probe | Startup probe too short for JVM warm-up; missing config / Workload Identity binding |
| CPU throttling, latency spikes | `container_cpu_cfs_throttled_periods_total` | CPU limit too low for GC/JIT threads; prefer requests without CPU limits, or size `-XX:ActiveProcessorCount` |
| 503s from ingress | `kubectl get endpoints`, readiness | Readiness failing, or pods killed before the mesh drains them → `preStop` sleep + graceful shutdown (in the Helm chart) |
| One pod slow, others fine | Istio outlier detection, `kubectl top` | Noisy neighbor / GC thrash → outlier detection ejects it; investigate with JFR from that pod |
| Calls between services denied | `istioctl x authz check <pod>` | AuthorizationPolicy principal mismatch (service account name) |
| `connection reset` under load | NetworkPolicy, conntrack, connection pools | Pool exhaustion; check HikariCP / Spanner session pool / Istio `connectionPool` limits |

Pulling evidence from a GKE pod:

```bash
kubectl exec -n commerce deploy/catalog-service -- jcmd 1 Thread.print -l > threads.txt
kubectl exec -n commerce deploy/catalog-service -- jcmd 1 JFR.dump name=continuous filename=/diagnostics/now.jfr
kubectl cp commerce/<pod>:/diagnostics/now.jfr ./now.jfr
kubectl debug -n commerce <pod> -it --image=eclipse-temurin:21-jdk --target=app   # ephemeral JDK container if the image is JRE-only
```
