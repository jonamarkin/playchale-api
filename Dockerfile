# The PlayChale API as a container image. Build it from this folder:
#   docker build -t playchale-api .
# CI builds and publishes it on every push to main (.github/workflows/ci.yml).

# 1. Build the jar. Tests run in CI before this, so they're skipped here.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q -DskipTests package \
 && java -Djarmode=tools -jar target/playchale-api-*.jar extract --layers --launcher --destination /extracted

# 2. Run it. Only the Java runtime and the app; layers ordered from least to most often changed, so a
# code change only replaces the last, small layer.
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 playchale
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER playchale

# Logs as JSON lines (Elastic Common Schema), each carrying its request's ID, for whatever collects
# them. On a laptop (./mvnw spring-boot:run) they stay plain text.
ENV LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs
# Use most of the container's memory for the heap, leaving room for the rest of the JVM.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
