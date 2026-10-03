# ---------- Build ----------
FROM gradle:8.10-jdk21 AS build
WORKDIR /app
COPY settings.gradle.kts build.gradle.kts ./
COPY src ./src
RUN gradle installDist --no-daemon

# ---------- Runtime ----------
FROM eclipse-temurin:21-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg curl ca-certificates unzip \
 && ARCH="$(uname -m)" \
 && if [ "$ARCH" = "aarch64" ]; then YT=yt-dlp_linux_aarch64; DENO=deno-aarch64-unknown-linux-gnu; \
    else YT=yt-dlp_linux; DENO=deno-x86_64-unknown-linux-gnu; fi \
 && curl -fsSL "https://github.com/yt-dlp/yt-dlp/releases/latest/download/$YT" -o /usr/local/bin/yt-dlp \
 && chmod +x /usr/local/bin/yt-dlp \
 && curl -fsSL "https://github.com/denoland/deno/releases/latest/download/$DENO.zip" -o /tmp/deno.zip \
 && unzip -o /tmp/deno.zip -d /usr/local/bin && rm /tmp/deno.zip \
 && rm -rf /var/lib/apt/lists/*

RUN useradd -m appuser
USER appuser
WORKDIR /app
COPY --from=build /app/build/install/audio-server/ ./

ENV PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s \
  CMD curl -fs http://localhost:8080/health || exit 1

CMD ["bin/audio-server"]
