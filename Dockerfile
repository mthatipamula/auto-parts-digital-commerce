# One recipe for every service: docker build --build-arg MODULE=checkout-service .
FROM eclipse-temurin:21-jdk AS build
ARG MODULE
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY commerce-common commerce-common
COPY gateway gateway
COPY catalog-service catalog-service
COPY inventory-service inventory-service
COPY checkout-service checkout-service
COPY payment-service payment-service
COPY account-service account-service
RUN --mount=type=cache,target=/root/.gradle,sharing=locked ./gradlew :${MODULE}:bootJar -x test --no-daemon -q \
    && cp ${MODULE}/build/libs/${MODULE}-*[0-9].jar /app.jar \
    && java -Djarmode=tools -jar /app.jar extract --layers --launcher --destination /layers

# JDK runtime so jcmd/jfr are available for diagnostics; a production image can stay JRE-only and use
# `kubectl debug` with an ephemeral JDK container instead (see docs/jvm-diagnostics.md).
FROM eclipse-temurin:21-jdk
RUN useradd --system --uid 10001 app && mkdir -p /diagnostics && chown app /diagnostics
WORKDIR /app
COPY --from=build /layers/dependencies/ ./
COPY --from=build /layers/spring-boot-loader/ ./
COPY --from=build /layers/snapshot-dependencies/ ./
COPY --from=build /layers/application/ ./
USER 10001
# JVM defaults for containers (see docs/jvm-diagnostics.md). JDK_JAVA_OPTIONS, not JAVA_TOOL_OPTIONS: only the `java`
# launcher reads it, so diagnostic tools (jcmd, jfr) don't start their own recordings or rotate the service's GC log.
#
# - heap sized from the container memory limit, leaving room for metaspace, threads and direct buffers
# - G1 with a pause-time goal; GC log rotated on disk for post-mortems
# - Java Flight Recorder always on (low overhead), dumped on exit; heap dump on OutOfMemoryError
ENV JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=70 -XX:InitialRAMPercentage=50 -XX:+UseG1GC -XX:MaxGCPauseMillis=100 \
 -Xlog:gc*,safepoint:file=/diagnostics/gc.log:time,uptime,level,tags:filecount=5,filesize=10m \
 -XX:StartFlightRecording=name=continuous,maxage=30m,maxsize=200m,dumponexit=true,filename=/diagnostics/ \
 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/diagnostics -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
