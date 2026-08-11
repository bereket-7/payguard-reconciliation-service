# See the note in payguard-payment-service/Dockerfile: the previous single-stage form copied a jar
# from the builder's target/ directory, so the image contents depended on local build state.
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace

COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app

# uid 10001 matches runAsUser in k8s/base/reconciliation-service/deployment.yml; runAsNonRoot in the
# pod spec will not start an image that would run as root.
RUN useradd --system --uid 10001 --create-home appuser

COPY --from=build /workspace/target/reconciliation-service-*.jar /app/app.jar

USER appuser

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
