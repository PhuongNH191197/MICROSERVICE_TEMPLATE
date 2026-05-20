FROM eclipse-temurin:17-jre-alpine
RUN apk add --no-cache ffmpeg
WORKDIR /app
COPY target/audio-generation-service-*.jar app.jar
EXPOSE 8092
ENTRYPOINT ["java", "-jar", "app.jar"]
