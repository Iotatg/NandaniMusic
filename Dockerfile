# Build stage
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# Runtime
FROM eclipse-temurin:17-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends \
        ffmpeg ca-certificates curl unzip python3 \
    && curl -fsSL https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp \
        -o /usr/local/bin/yt-dlp && chmod +x /usr/local/bin/yt-dlp \
    && curl -fsSL -o /tmp/ntg.zip \
        https://github.com/pytgcalls/ntgcalls/releases/download/v2.2.5/ntgcalls.linux-x86_64-shared_libs.zip \
    && unzip -q /tmp/ntg.zip -d /tmp/ntg \
    && cp /tmp/ntg/lib/libntgcalls.so /usr/local/lib/ \
    && ldconfig && rm -rf /tmp/ntg /tmp/ntg.zip /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /src/target/nandani-music.jar /app/nandani-music.jar
COPY locales /app/locales

ENV PORT=8000 \
    NANDANI_TRANSPORT=botapi \
    NTGCALLS_LIB=/usr/local/lib/libntgcalls.so

EXPOSE 8000
ENTRYPOINT ["java", "-Xms128m", "-Xmx512m", "-jar", "/app/nandani-music.jar"]
