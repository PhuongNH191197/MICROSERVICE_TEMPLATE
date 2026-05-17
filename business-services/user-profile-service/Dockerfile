FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY target/user-profile-service-1.0.0-SNAPSHOT.jar app.jar
EXPOSE 8091
ENTRYPOINT ["java", "-jar", "app.jar"]
