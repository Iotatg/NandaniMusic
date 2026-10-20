#!/usr/bin/env bash
# NandaniMusic — full VPS bootstrap (Ubuntu/Debian x86_64)
#
#   sudo bash deploy.sh            # install deps, build, install systemd unit
#   sudo bash deploy.sh --no-service
#
# Installs:
#   * JDK 17 + Maven (build only)
#   * ffmpeg + yt-dlp          (audio extraction / streaming)
#   * libntgcalls 2.2.5        (voice-chat engine, only used with assistants)
#   * optional libtdjson       (needed for assistant accounts)
set -euo pipefail
cd "$(dirname "$0")"

WITH_SERVICE=1
[ "${1:-}" = "--no-service" ] && WITH_SERVICE=0

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }

say "Installing system packages"
sudo apt-get update -qq
sudo apt-get install -y -qq openjdk-17-jdk-headless maven ffmpeg curl unzip ca-certificates python3-pip
# yt-dlp: distro package is often stale, prefer the official binary
if ! command -v yt-dlp >/dev/null 2>&1; then
  sudo curl -fsSL https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp \
    -o /usr/local/bin/yt-dlp
  sudo chmod +x /usr/local/bin/yt-dlp
fi

say "Installing libntgcalls 2.2.5"
if [ ! -f /usr/local/lib/libntgcalls.so ]; then
  tmp=$(mktemp -d)
  curl -fsSL -o "$tmp/ntg.zip" \
    https://github.com/pytgcalls/ntgcalls/releases/download/v2.2.5/ntgcalls.linux-x86_64-shared_libs.zip
  unzip -q -o "$tmp/ntg.zip" -d "$tmp"
  sudo cp "$tmp/lib/libntgcalls.so" /usr/local/lib/
  sudo ldconfig
  rm -rf "$tmp"
fi
echo "    libntgcalls: $(ls -lh /usr/local/lib/libntgcalls.so | awk '{print $5}')"

say "Building NandaniMusic"
mvn -B -q package -DskipTests
ls -lh target/nandani-music.jar

if [ ! -f .env ]; then
  say "Creating .env from template"
  cp .env.example .env
  echo "    !! edit .env and set API_ID / API_HASH / TOKEN, then re-run"
fi

if [ "$WITH_SERVICE" = "1" ]; then
  say "Installing systemd unit"
  sudo install -d /opt/nandani-music
  sudo cp -r target/nandani-music.jar locales .env /opt/nandani-music/
  sudo useradd -r -s /usr/sbin/nologin nandani 2>/dev/null || true
  sudo chown -R nandani:nandani /opt/nandani-music
  sudo tee /etc/systemd/system/nandani-music.service >/dev/null <<'UNIT'
[Unit]
Description=NandaniMusic Telegram voice-chat bot
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=nandani
WorkingDirectory=/opt/nandani-music
Environment=JAVA_OPTS=-Xms128m -Xmx512m
ExecStart=/usr/bin/java -Xms128m -Xmx512m -jar /opt/nandani-music/nandani-music.jar
Restart=always
RestartSec=5
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
UNIT
  sudo systemctl daemon-reload
  sudo systemctl enable nandani-music
  sudo systemctl restart nandani-music
  say "Status"
  sudo systemctl --no-pager --lines=15 status nandani-music || true
else
  say "Done — start with ./run.sh"
fi
