# SOSync API - the image that runs on the server.
#
# The jar is compiled on the laptop and copied up as app.jar by deploy.ps1; this image is then
# built on the server itself, so it always matches the server's CPU. Oracle's free Ampere VMs
# are ARM, and eclipse-temurin publishes ARM and x86 builds under the same tag, so this file
# works unchanged on either.
FROM eclipse-temurin:21-jre

WORKDIR /app
COPY app.jar app.jar

# Let the heap use most of the machine, whatever size VM this lands on.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
