ARG GRADLE_IMAGE=gradle:8.14.3-jdk21
ARG RUNTIME_IMAGE=eclipse-temurin:21-jre-jammy

FROM ${GRADLE_IMAGE} AS builder
ARG SERVICE_NAME
WORKDIR /build
COPY settings.gradle .
COPY build.gradle .
COPY gradle gradle
COPY gradlew gradlew
COPY gradlew.bat gradlew.bat
COPY merchant/src merchant/src
COPY payment/src payment/src
COPY fds/src fds/src
COPY card/src card/src
COPY failure/src failure/src
RUN gradle --no-daemon :${SERVICE_NAME}:clean :${SERVICE_NAME}:bootJar

FROM ${RUNTIME_IMAGE}
ARG SERVICE_NAME
WORKDIR /app
COPY --from=builder /build/${SERVICE_NAME}/build/libs/${SERVICE_NAME}.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
