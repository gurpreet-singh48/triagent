# Build context is the repo root (see docker-compose.yml) so later phases
# can COPY synthetic-generator/templates/ into this image too.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY backend/pom.xml ./pom.xml
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
COPY synthetic-generator/templates ./synthetic-generator/templates
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
