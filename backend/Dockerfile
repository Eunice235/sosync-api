# Builds and runs the API from source. Used by Render (see ../render.yaml); works anywhere
# Docker does: docker build -t sosync-api . && docker run -p 8090:8090 sosync-api

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Wrapper and build files first, so the dependency download is cached between code changes.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
# A checkout on Windows can leave CRLF line endings on the wrapper, which Linux refuses to run.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null

COPY src src
# Tests need Docker (Testcontainers), which a build container does not have. They run locally.
RUN ./gradlew --no-daemon bootJar -x test && cp build/libs/sosync-api-*.jar /app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app.jar app.jar

# Sized for a 512 MB free instance: cap the heap as a share of the container, use the lighter
# collector, and trim thread stacks.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k -XX:TieredStopAtLevel=1"

EXPOSE 8090
ENTRYPOINT ["java", "-jar", "app.jar"]
