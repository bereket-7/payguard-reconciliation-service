FROM eclipse-temurin:17-jre
COPY target/reconciliation-service-*.jar /app.jar
ENTRYPOINT ["java","-jar","/app.jar"]
