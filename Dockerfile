# === Stage 1: build ===
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn -B -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests clean package \
 && /bin/sh -lc 'JAR=$(ls -1 target/*-shaded.jar 2>/dev/null || ls -1 target/*.jar | head -n 1); \
                 echo "Using JAR: $JAR"; \
                 cp "$JAR" target/app.jar'

# === Stage 2: runtime ===
FROM eclipse-temurin:17-jre
WORKDIR /app

# основной jar
COPY --from=build /app/target/app.jar /app/app.jar

# каталог для SQLite (будет создан файл БД при первом запуске)
RUN mkdir -p /data

EXPOSE 8080
VOLUME ["/data"]

ENTRYPOINT ["java","-jar","/app/app.jar"]