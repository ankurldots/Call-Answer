# Image for the :agent service. Build context is the repository root.
# The distribution is built on the host first: ./gradlew :agent:installDist
# (scripts/build-push-agent.sh does both steps).
FROM eclipse-temurin:21-jre

RUN useradd --system --uid 10001 --no-create-home agent
WORKDIR /opt/agent
COPY agent/build/install/agent/ /opt/agent/
USER agent

ENV PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080

# The Gradle start script ends with `exec java`, so the JVM is PID 1 and receives
# SIGTERM directly. The JVM shutdown hook (DrainCoordinator) stops accepting new
# calls and lets active calls finish for up to 120 s. The ECS container definition
# must set stopTimeout to 120 (the ECS maximum) so ECS does not send SIGKILL earlier.
ENTRYPOINT ["/opt/agent/bin/agent"]
