<p align="center">
  <img src="https://capsule-render.vercel.app/api?type=waving&color=gradient&customColorList=6,11,19,26&height=220&section=header&text=Nandani%20Music&fontSize=60&fontColor=ffffff&animation=fadeIn&fontAlignY=38&desc=Next-Generation%20Telegram%20Voice%20Chat%20Music%20Engine&descAlignY=60&descAlign=50" width="100%"/>
</p>

<p align="center">
  <img src="https://readme-typing-svg.demolab.com?font=Fira+Code&weight=600&size=20&pause=1000&color=00E5FF&center=true&vCenter=true&width=640&lines=High-Performance+Java+Streaming+Engine;Raw+MTProto+2.0+%E2%80%94+No+Bot+HTTP+API;Ultra-Low+Latency+Voice+Chat+Audio+%26+Video;Live+Message+Editing+%2B+Download+CANCEL;Multi-Language+YAML+Locales;Developed+%26+Maintained+by+%40im_abz" alt="Typing SVG" />
</p>

<p align="center">
  <a href="https://github.com/Iotatg/NandaniMusic/stargazers">
    <img src="https://img.shields.io/badge/GitHub-Stars-FFD700?style=for-the-badge&logo=github&logoColor=white" alt="GitHub Stars">
  </a>
  <a href="https://github.com/Iotatg/NandaniMusic/fork">
    <img src="https://img.shields.io/badge/GitHub-Forks-00C853?style=for-the-badge&logo=github&logoColor=white" alt="GitHub Forks">
  </a>
  <a href="https://www.java.com/">
    <img src="https://img.shields.io/badge/Java-21-FF7800?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java">
  </a>
  <a href="https://core.telegram.org/api/mtproto">
    <img src="https://img.shields.io/badge/MTProto-Raw%202.0-2CA5E0?style=for-the-badge&logo=telegram&logoColor=white" alt="MTProto">
  </a>
  <a href="https://github.com/Iotatg/NandaniMusic/blob/main/LICENSE">
    <img src="https://img.shields.io/badge/License-MIT-blue?style=for-the-badge&logo=opensourceinitiative&logoColor=white" alt="License">
  </a>
</p>

---

## ✨ Features & Highlights

- ⚡ **Ultra-Low Latency Streaming:** A lean Java 21 core wired straight into `ntgcalls` (JNA), delivering crystal-clear voice-chat playback — audio **and** 720p video — with minimal overhead.
- 📡 **Raw MTProto 2.0 Client:** A from-scratch MTProto stack (TCP intermediate, AES-IGE, SHA-256 key derivation, `phone.joinGroupCall` SDP exchange) instead of the Bot HTTP API, so every status message is **edited in place** through the full search → download → now-playing chain.
- 🎵 **Multi-Platform Provider Tiers:** Resolution order **Iota API (self-hosted, 95)** → **Yuki API (85)** → **yt-dlp with YouTube cookies** → direct HTTP. Audio, video, live streams, Telegram files and direct URLs all supported.
- 🧠 **Iota Stream Proxy:** The bundled [Iota-api](https://github.com/Iotatg/Iota-api) backend fans out across parallel googlevideo connections (multi-connection range streaming), so playback starts fast and stays smooth.
- 🍪 **Cookie-Powered Extraction:** Drop your YouTube `cookies.txt` in `data/` and yt-dlp sails past the "Sign in to confirm you're not a bot" wall.
- 🖼️ **Signature Cards:** Bold-first-letter + small-caps titles, expandable blockquotes, photo now-playing cards with auto artwork, and compact clickable queue lists.
- 🛑 **Real Download Cancellation:** Live progress editing plus a working `/cancel` that aborts the transfer mid-stream.
- 🎛️ **Full Playback Controls:** Seek, speed, mute, loop, shuffle, autoplay, force-play variants and complete queue management.
- 🌐 **Multi-Language Engine:** Hot-reloadable YAML locale packs with blockquote-aware formatting.
- 🛡️ **Admin & Security:** Granular sudo management, per-chat authorization, owner-only maintenance flows.

---

## 🚀 Quick Deploy

<details>
  <summary><b>🖥️ [Click to Expand] VPS / Linux Server Build Guide</b></summary>
  <br>

  ### 📋 Prerequisites
  ```bash
  sudo apt-get update && sudo apt-get upgrade -y
  sudo apt-get install -y git openjdk-21-jdk maven ffmpeg python3-pip nodejs
  sudo pip3 install yt-dlp
  ```

  ### 🚀 Step-by-Step Setup
  ```bash
  # 1. Clone the repository
  git clone https://github.com/Iotatg/NandaniMusic.git
  cd Nandani-Music

  # 2. (Optional but recommended) Deploy the Iota backend
  git clone https://github.com/Iotatg/Iota-api.git ../iota-api
  pip3 install -r ../iota-api/requirements.txt
  #    point IOTA_API_URL at it and give it your YouTube cookies

  # 3. Configure environment
  nano .env   # API_ID, API_HASH, TOKEN, OWNER_ID, STRING_SESSIONS, ...

  # 4. YouTube cookies (fixes video extraction)
  #    Export with a browser extension ("Get cookies.txt LOCALLY")
  #    and save as data/cookies.txt  (or set COOKIES_LINK)

  # 5. Compile and run
  mvn -q package -DskipTests
  java -Xms128m -Xmx512m -jar target/nandani-music.jar
  ```

  ### 🔄 Running 24/7 in Background
  **Method 1: Using Tmux (Recommended)**
  ```bash
  tmux new -s nandanimusic
  java -jar target/nandani-music.jar
  # Press Ctrl+B then D to detach. Re-attach anytime with: tmux a -t nandanimusic
  ```

  **Method 2: Using Systemd Service**
  ```bash
  sudo nano /etc/systemd/system/nandanimusic.service
  ```
  Paste the following unit configuration:
  ```ini
  [Unit]
  Description=Nandani Music Telegram Bot
  After=network.target

  [Service]
  Type=simple
  User=ubuntu
  WorkingDirectory=/home/ubuntu/Nandani-Music
  ExecStart=/usr/bin/java -Xms128m -Xmx512m -jar target/nandani-music.jar
  Restart=always
  RestartSec=5

  [Install]
  WantedBy=multi-user.target
  ```
  Enable and start the service:
  ```bash
  sudo systemctl daemon-reload
  sudo systemctl enable nandanimusic
  sudo systemctl start nandanimusic
  sudo systemctl status nandanimusic
  ```
</details>

---

## ⚙️ Configuration Variables

<details open>
  <summary><b>🔑 Environment Variables Reference Table</b></summary>
  <br>

  | Variable | Required | Default | Description |
  | :--- | :---: | :---: | :--- |
  | `API_ID` | **Yes** | — | Telegram API ID from [my.telegram.org](https://my.telegram.org) |
  | `API_HASH` | **Yes** | — | Telegram API Hash from [my.telegram.org](https://my.telegram.org) |
  | `TOKEN` | **Yes** | — | Telegram Bot Token from [@BotFather](https://t.me/BotFather) |
  | `OWNER_ID` | **Yes** | — | Telegram User ID of the bot owner |
  | `STRING_SESSIONS` | **Yes** | — | Assistant user session string(s), comma-separated (pyrogram format) |
  | `IOTA_API_URL` | No | — | Self-hosted [Iota-api](https://github.com/Iotatg/Iota-api) endpoint (search/stream tier) |
  | `IOTA_API_KEY` | No | — | Iota API key (`iota_…`, created via the admin API) |
  | `YUKI_API_URL` | No | — | Yuki gateway base or `{VIDEO_ID}` template |
  | `YUKI_API_KEY` | No | — | Yuki API key |
  | `COOKIES_LINK` | No | — | YouTube cookies file path or remote URL (`data/cookies.txt` picked up automatically) |
  | `YTDLP_PROXY` | No | — | Optional HTTP proxy for yt-dlp extraction |
  | `DURATION_LIMIT` | No | `9000` | Max track duration in seconds |
  | `QUEUE_LIMIT` | No | `500` | Max tracks allowed in queue per chat |
  | `DEFAULT_LANG` | No | `en` | Default language code (`en`, `hi`, `ar`, `tr`) |
  | `LOGGER_ID` | No | `0` | Telegram Chat ID for logs and error reports |
  | `DEV_URL` | No | — | Developer profile link shown on repo cards |
  | `START_IMG_URL` | No | — | Media shown on `/start` greeting |
  | `PORT` | No | `8000` | HTTP health/metrics server port |
</details>

---

## 🎮 Bot Commands Reference

<details>
  <summary><b>🎵 [Click to Expand] Playback & Stream Commands</b></summary>
  <br>

  | Command | Description |
  | :--- | :--- |
  | `/play <query/url>` | Streams requested song into group voice chat |
  | `/vplay <query/url>` | Streams track with 720p video rendering |
  | `/playforce <query/url>` | Force plays a single track as owner/sudo |
  | `/skip` | Skips current track to the next item in queue |
  | `/pause` | Pauses active playback stream |
  | `/resume` | Resumes paused audio stream |
  | `/stop` | Stops playback and clears the entire playlist queue |
  | `/seek <seconds>` | Seeks playback to a specific timestamp |
  | `/speed <1.0-3.0>` | Adjusts voice-chat playback speed |
  | `/mute` / `/unmute` | Mutes or unmutes the stream audio |
  | `/queue` | Displays the current playlist and upcoming tracks |
  | `/replay` | Replays the current playing song from beginning |
  | `/cancel` | Aborts the active track download mid-transfer |
</details>

<details>
  <summary><b>👥 [Click to Expand] Group Admin & Settings Commands</b></summary>
  <br>

  | Command | Description |
  | :--- | :--- |
  | `/auth <user>` | Authorizes non-admin user to use playback commands |
  | `/unauth <user>` | Revokes bot access authorization from a user |
  | `/autoplay` | Toggles continuous auto-play when queue empties |
  | `/loop` | Cycles loop mode (off / single / queue) |
  | `/shuffle` | Shuffles the current playlist queue |
  | `/lang` | Opens the interactive language selector panel |
  | `/settings` | Opens the interactive visual settings control panel |
  | `/reload` | Refreshes admin caches and locale packs live |
  | `/askip` | Personal skip that only advances your own request |
</details>

<details>
  <summary><b>🛡️ [Click to Expand] Bot Owner & Sudo Commands</b></summary>
  <br>

  | Command | Description |
  | :--- | :--- |
  | `/addsudo <user>` | Adds a new user to sudoers list |
  | `/delsudo <user>` | Removes a user from sudoers list |
  | `/sudolist` | Shows list of all current bot sudoers |
  | `/stats` | Shows server performance, memory, and uptime metrics |
  | `/broadcast <msg>` | Broadcasts announcements to all served chats |
  | `/purge <n>` | Deletes last n messages from the current chat |
  | `/ping` | Live latency and response-time benchmark |
</details>

---

## 📁 Project Architecture

```
Nandani-Music/
├── src/main/java/music/nandani/
│   ├── mtproto/          # From-scratch MTProto 2.0 core (RPC, crypto, TCP transport)
│   ├── tg/               # Bot session, updates, markup, HTML formatting
│   ├── ntg/              # ntgcalls FFI bindings via JNA (voice-chat media engine)
│   ├── voice/            # Voice engine: join, stream, re-join on audio<->video
│   ├── modules/          # Command modules (play, queue, admin, settings, ...)
│   ├── platform/         # Provider tiers: Iota -> Yuki -> yt-dlp -> HTTP
│   ├── media/            # yt-dlp wrapper + downloads
│   ├── i18n/             # Hot-reloadable YAML locale engine
│   └── model/            # Track, queue and state models
├── src/main/resources/
├── locales/              # YAML multi-language packs (en / hi / ar / tr)
├── data/                 # Sessions, cookies, downloads (created at runtime)
└── pom.xml               # Maven build definition
```

---

## 📜 License

Distributed under the **MIT License**. See the [`LICENSE`](./LICENSE) file for full details.

<p align="center">
  <img src="https://capsule-render.vercel.app/api?type=waving&color=gradient&customColorList=6,11,19,26&height=100&section=footer" width="100%"/>
</p>

<p align="center">
  <b>Developed & Maintained with ❤️ by <a href="https://t.me/im_abz">abz</a></b>
</p>
