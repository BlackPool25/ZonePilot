# Stage 1: Dependency cache layer (only invalidated when pom.xml changes)
FROM eclipse-temurin:25-jdk AS deps
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline -q

# Stage 2: Build (only re-runs compilation when src/ changes)
FROM deps AS builder
COPY src/ src/
RUN ./mvnw package -DskipTests -q

# Stage 3: Runtime (minimal JRE image)
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=builder /build/target/backend-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
