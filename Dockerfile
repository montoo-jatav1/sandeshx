FROM gradle:8.9-jdk17 AS build
WORKDIR /app
COPY . .
RUN gradle clean build -x test --no-daemon

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
# The Ktor Gradle plugin produces a fat jar named "<project>-all.jar" alongside a thin jar.
# Copy ONLY the fat jar (it bundles all dependencies needed to run standalone).
COPY --from=build /app/build/libs/*-all.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
