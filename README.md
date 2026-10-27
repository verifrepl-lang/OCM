<p align="center">
  <img src="docs/logo.svg" alt="OpenCode logo" width="96">
</p>
<p align="center">The open source AI coding agent, on your phone.</p>
<p align="center">
  <a href="https://github.com/samartsevmaksim5-ops/OpenCode-Mobile/releases/latest"><img alt="Release" src="https://img.shields.io/github/v/release/samartsevmaksim5-ops/OpenCode-Mobile?style=flat-square" /></a>
  <a href="https://github.com/samartsevmaksim5-ops/OpenCode-Mobile/actions/workflows/build.yml"><img alt="Build status" src="https://img.shields.io/github/actions/workflow/status/samartsevmaksim5-ops/OpenCode-Mobile/build.yml?style=flat-square&branch=main" /></a>
  <a href="https://t.me/OpenCodeMob"><img alt="Telegram" src="https://img.shields.io/badge/telegram-OpenCodeMob-blue?style=flat-square" /></a>
</p>

---

### Installation

Download the latest `OpenCode-<version>-<build>.apk` from the
[Releases](https://github.com/samartsevmaksim5-ops/OpenCode-Mobile/releases/latest) page and install it.

- Android 11 or newer, 64-bit ARM (arm64-v8a)
- The first launch unpacks a full Linux environment (a few minutes, once)
- Sign in with your Google account; every account gets its own private workspace
- Later updates arrive over the air: **Settings > Updates**

Current app version: **1.0.6**. It runs the **OpenCode 1.x** line.

### What is inside

OpenCode Mobile is an Android app that bundles a complete Linux userland, `proot`
and the OpenCode 1.x terminal agent. The interface is built into the app: you do not open a
website, and chats stay readable without a network connection.

- Everything from the terminal works on the phone: `/init`, `/connect`, `/models`, `/undo`, `/share`
  and every other command
- MCP servers and plugins, configured the same way as on desktop
- Chat with the agent, live terminal output and the project file tree, with language icons next to files
- Portrait and landscape layouts
- Sounds and notifications when a task finishes, when the agent needs you, and when a quota runs out
- Runs in the background as a foreground service so it is not killed
- Upload `AGENTS.md` and agent `.md` files from the phone's file picker in **Settings > Agents**
- Access to `/storage/emulated/0` and `Download` from inside the Linux environment
- Per-user isolation: private home folder, own local port and own random password on every install

### Agents

OpenCode includes two built-in agents you can switch between in the prompt bar.

- **build** - Default, full-access agent for development work
- **plan** - Read-only agent for analysis and code exploration
  - Denies file edits by default
  - Asks permission before running bash commands
  - Ideal for exploring unfamiliar codebases or planning changes

Also included is a **general** subagent for complex searches and multistep tasks. This is used internally and can be invoked using `@general` in messages.

### Contributing

Pull requests are welcome. The build runs entirely in GitHub Actions: push to `main` and an APK is published
to Releases. See `docs/SETUP.md` for the one-time configuration.

### Building on OpenCode

OpenCode Mobile is not built by the OpenCode team and is not affiliated with them in any way.
It packages their MIT-licensed 1.x release inside an Android app.

---

**Join our community** [Telegram](https://t.me/OpenCodeMob)
