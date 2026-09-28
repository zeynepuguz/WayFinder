# Build: docker build -t nomi-backend backend/
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src/ src/
RUN ./mvnw -q -B package -DskipTests && cp target/*.jar app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
# /data/photos: user photos. A named volume mounted there takes over this owner, so the non-root user can write
RUN useradd --system --uid 10001 nomi \
    && mkdir -p /data/photos && chown nomi /data/photos
COPY --from=build /app/app.jar app.jar
USER nomi
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
