FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /workspace
COPY gradlew .
COPY gradle gradle
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
COPY settings.gradle .
COPY build.gradle .
RUN ./gradlew dependencies --no-daemon || true
COPY src src
RUN ./gradlew bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && groupadd -r filedepot && useradd -r -g filedepot filedepot \
    && mkdir -p /app/logs && chown -R filedepot:filedepot /app
COPY --from=builder /workspace/build/libs/*.jar app.jar
RUN chown filedepot:filedepot app.jar
USER filedepot:filedepot
EXPOSE 8080 8081
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar --spring.profiles.active=prod"]
