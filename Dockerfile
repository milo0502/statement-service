# ---- build stage ----
FROM maven:3.9.12-eclipse-temurin-25-noble AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -e -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -e -DskipTests package

# ---- runtime stage ----
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
ENV JAVA_TOOL_OPTIONS="-XX:InitialRAMPercentage=25 -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    SPRING_SERVLET_MULTIPART_LOCATION="/tmp/statement-service-uploads"
RUN apk upgrade --no-cache \
    && addgroup -g 10001 -S statement \
    && adduser -u 10001 -S -G statement -h /app -s /sbin/nologin statement \
    && mkdir -p /tmp/statement-service-uploads \
    && chown -R statement:statement /app /tmp/statement-service-uploads
COPY --chown=statement:statement --from=build /app/target/statement-service.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
