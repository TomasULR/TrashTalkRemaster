### --- Build stage ---
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src/ src/
RUN mvn -B -q -DskipTests package \
 && cp target/trashtalk-backend-*.jar /workspace/app.jar

### --- Runtime stage ---
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN groupadd --system trashtalk && useradd --system --gid trashtalk trashtalk \
 && apt-get update && apt-get install -y --no-install-recommends wget \
 && rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/app.jar /app/app.jar
RUN chown trashtalk:trashtalk /app/app.jar
USER trashtalk

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8080/actuator/health || exit 1

ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
