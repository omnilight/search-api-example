# syntax=docker/dockerfile:1

# ---- Stage 1: native build inside the Mandrel (GraalVM) builder image ----
# Mandrel 25.x lives on JDK 25 and is what Quarkus 3.40 supports for native.
FROM quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-25 AS build
USER root
WORKDIR /project

COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/wrapper gradle/wrapper

# Only main sources are needed for the native image: test-only edits and the
# docs around the repo do not invalidate this stage.
COPY src/main src/main

# Gradle home lives in a BuildKit cache mount, not in an image layer:
# dependency jars, the wrapper distribution and the Gradle build cache
# (org.gradle.caching=true) survive image rebuilds — even when build files
# change — and never bloat the layers. sharing=locked keeps concurrent
# builds from corrupting the cache. With the mount in place a separate
# dependency warm-up task is unnecessary: the first build fills the cache,
# later builds reuse it.
RUN --mount=type=cache,id=nevis-gradle,target=/root/.gradle,sharing=locked \
    ./gradlew --no-daemon build -x test \
        -Dquarkus.native.enabled=true \
        -Dquarkus.package.jar.enabled=false \
        -Dquarkus.native.container-build=false \
        -Dquarkus.native.additional-build-args=-J-Xmx3g

# ---- Stage 2: single native binary on a minimal runtime image ----
FROM quay.io/quarkus/quarkus-micro-image:2.0
WORKDIR /work
COPY --from=build --chown=1001:0 /project/build/*-native-image-source-jar/*-runner /work/application
RUN chmod 775 /work
USER 1001
EXPOSE 8080
ENTRYPOINT ["/work/application"]
