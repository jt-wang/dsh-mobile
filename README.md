<picture><source media="(max-width: 767px)" srcset="docs/assets/dsh-mobile-banner-mobile.png"><img src="docs/assets/dsh-mobile-banner.png" alt="DSH Mobile screens for Android. Chats and notifications are on the phone; the agent works on the computer."></picture>

# DSH Mobile

Case study on the website: <https://logachev.net/#case-dsh-mobile> · [Technical write-up (in Russian) on Habr](https://habr.com/ru/articles/1099110/)

[![Latest release](https://img.shields.io/github/v/release/pavel-logachev/dsh-mobile?style=flat-square&labelColor=0b0f14&label=release&color=c3f451)](https://github.com/pavel-logachev/dsh-mobile/releases/latest) [![CI checks](https://img.shields.io/github/actions/workflow/status/pavel-logachev/dsh-mobile/ci.yml?branch=main&style=flat-square&labelColor=0b0f14&label=CI)](https://github.com/pavel-logachev/dsh-mobile/actions/workflows/ci.yml) [![License](https://img.shields.io/github/license/pavel-logachev/dsh-mobile?style=flat-square&labelColor=0b0f14&label=license&color=7f99ad)](<LICENSE>)

English | [Русский](README.ru.md)

**Your [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) agent runs on your computer. From your Android phone you assign tasks with photos and files, read replies in real time, answer the agent's questions and get an “Answer ready” notification.**

[Download APK 0.8.2](https://github.com/pavel-logachev/dsh-mobile/releases/tag/v0.8.2) · [Installation](#installation) · [Architecture](#how-it-works) · [Security](#security)

> An unofficial project by Pavel Logachev. Developed independently of DeepSeek.

Android 8.0+ · QR code pairing · Wi‑Fi or Tailscale

Models, tools and subscriptions stay on the computer. DSH Mobile has no cloud server.

## What you can do from your phone

- **Open chats from all projects.** Search chats and filter them by project. The project list follows the DSH sidebar and updates without re-pairing.
- **Assign tasks.** Pick a project and a preset and write the task right away: the app creates a chat and sends the text once.
- **Send photos and files.** A shot from the camera, photos through the system photo picker (Photo Picker), files through the system file picker. Uploading starts as soon as you pick them. One message can carry up to 20 attachments with a total size of up to 200 MiB, each file up to 20 MiB. The agent receives PNG, JPEG, WebP and GIF images as images; files are handed to the DSH attachment store at the moment of sending. Unsent uploads expire on the computer after 10 minutes and never reach DSH.
- **Read the reply in real time.** Markdown, tables, code blocks with a copy button. Images from messages appear right in the conversation; tap one to open a full-screen viewer where you can zoom in, send it via Share or save it. Files from messages can be downloaded — up to 20 MiB. Service events are collapsed into an “Agent activity” line — the full text opens on tap. A long compacted chat whose older history cannot be restored within the limits opens with the latest messages and a note that the history is truncated.
- **Add messages and steer while the agent works.** By default a message joins the DSH queue and reaches the agent on its next turn. “Steer” delivers it at the agent's next step without stopping the current turn: hold the send button or tap ↳ on a queued message. You can also remove a message from the queue.
- **Answer the agent's questions.** Pick one or more options, write your own answer or skip the question. The option the agent recommends is marked but not preselected. Approvals of agent actions stay on the computer.
- **Rename a chat and change its model.** Both actions are in the ⋯ menu. The new model applies from the next model request, possibly as early as the next step of the running turn; a request that is already in progress is not switched.
- **Stop a run.** A separate Stop button asks for confirmation.
- **Get notifications.** For “Answer ready” and “Agent needs your decision” you choose separately between “With sound” (a pop-up banner with sound), “Silent” and “Off”. Each mode has its own Android channel; the app settings link to the system channel settings. Your earlier system choice is kept on update: a disabled channel stays disabled, a silent one stays silent. Sound accompanies every new event, multiple alerts are grouped, and the persistent connection notification is silent. A notification about an agent question opens that question directly. The warning “Some events may have been missed” disappears by itself once the computer confirms full recovery with nothing missed. There are no notifications for the chat that is open on screen. You can turn notifications off for individual projects and chats.
- **Come back to the conversation.** Message search, a draft for each chat and copying with a long press.

Attachments, “Steer”, answering questions, renaming, changing the model, viewing and downloading files, as well as the new notification features require companion plugin 0.7.0 or later. The notification-delay and send-admission fixes in the current release require plugin 0.7.2. The app shows each new feature only if the connected plugin reports that it supports it.

The theme follows the system one; you can also choose light or dark manually. The interface is in Russian and English: the language follows the system or is chosen in the app settings. Large font sizes are supported.

<picture><source media="(max-width: 767px)" srcset="docs/assets/dsh-mobile-screens-mobile.png"><img src="docs/assets/dsh-mobile-screens.png" alt="Three screens: chats from different projects, a Markdown reply while the agent is working, and choosing a project before a new chat."></picture>

The screenshots were taken in the real app with synthetic demo data. They contain no real chats or connected models.

## Screenshots

![DSH Mobile 0.8](docs/images/0.8/hero.png)

DSH Mobile 0.8: the agent works on your computer, and your chat with it is on your phone.

![Chats from all projects](docs/images/0.8/chats.png)

Chats from all DSH projects in one list.

![A message while the agent is working](docs/images/0.8/steer.png)

The agent is working and you add a message: to the queue, or “Steer” at the next step.

![Attachments in a message](docs/images/0.8/attachments.png)

Camera photos, gallery pictures and files — in one message to the agent.

![Agent question](docs/images/0.8/question.png)

An agent question: pick an option, write your own answer or skip it.

![Chat menu](docs/images/0.8/menu.png)

The ⋯ menu: renaming the chat and changing the model.

![Image viewer](docs/images/0.8/viewer.png)

An image from the chat in full screen: zoom in, share, save.

![Notifications](docs/images/0.8/notifications.png)

Notifications: the answer is ready, the agent has asked a question.

## How it works

A companion plugin is installed on the computer. It opens a narrow API to the paired phone over a secure connection but does not expose the DSH web interface to the outside.

```text
Android app
  │ HTTPS over Wi‑Fi or Tailscale
  │ or through your own relay
  ▼
Companion plugin inside DSH
  │
  ▼
Your projects and agents on the computer
```

The phone is only a client: the agent keeps working after you close the app. The main conversation history is stored in DSH on the computer.

The Android client is written in Kotlin and Jetpack Compose; the plugin runs on Node.js. Your own relay is an optional way to connect; the project does not provide a shared public relay.

## Installation

You can hand the detailed [setup guide](<docs/SETUP.md>) to your DSH agent. It contains environment checks, commands and separate approval of the installation, access rights and activation.

1. **Check the requirements.**

   Computer: Windows, DeepSeek Harness `0.2.0-rc.2` or `0.2.1-alpha.1`, Node.js 24.x, OpenSSL 3. Phone: Android 8.0 or later. Node 24 is also required by the DSH process the plugin runs in — having one suitable version on PATH is not enough.

2. **Download and verify the release files.**

   From [release 0.8.2](https://github.com/pavel-logachev/dsh-mobile/releases/tag/v0.8.2) you need the signed [dsh-mobile-0.8.2.apk](https://github.com/pavel-logachev/dsh-mobile/releases/download/v0.8.2/dsh-mobile-0.8.2.apk), the plugin archive [dsh-mobile-host-0.7.2.zip](https://github.com/pavel-logachev/dsh-mobile/releases/download/v0.8.2/dsh-mobile-host-0.7.2.zip) and their checksums: [dsh-mobile-0.8.2.apk.sha256](https://github.com/pavel-logachev/dsh-mobile/releases/download/v0.8.2/dsh-mobile-0.8.2.apk.sha256), [dsh-mobile-host-0.7.2.zip.sha256](https://github.com/pavel-logachev/dsh-mobile/releases/download/v0.8.2/dsh-mobile-host-0.7.2.zip.sha256). The archive contains companion plugin 0.7.2 for app 0.8.2. All files must come from the same release. `UNSIGNED-verification.apk` is a verification artifact, not a file to install.

   Specify the absolute paths to the downloaded files and compare the hashes with the `.sha256` files. If they do not match, stop.

   ```powershell
   $zip = '<ABSOLUTE_DOWNLOADED_HOST_ZIP>'
   $apk = '<ABSOLUTE_DOWNLOADED_SIGNED_APK>'
   (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
   (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
   # Compare exactly with the release's .sha256 files. A mismatch means STOP.
   ```

3. **Prepare the plugin on the computer.**

   Extract the [installer](<tools/install-host.ps1>) from the archive and read it. In the commands below, replace the placeholders: the path to the script and the computer's address on your home network or in Tailscale. If you do not need an additional address, remove the second element of the array.

   ```powershell
   $installer = '<ABSOLUTE_REVIEWED_INSTALL_HOST_PS1>'
   $hosts = @('<ACTUAL_PRIMARY_LAN_OR_TAILSCALE_HOST>', '<OPTIONAL_ADDITIONAL_SAN>')
   # Remove the second element if not needed. Substitute approved real values locally.
   & $installer -ZipPath $zip -Hosts $hosts -WhatIf
   & $installer -ZipPath $zip -Hosts $hosts
   ```

   This step prepares the files and configuration but does not yet connect the plugin to DSH.

4. **Activate the plugin.**

   After reviewing the proposed changes:

   ```powershell
   & $installer -ZipPath $zip -Hosts $hosts -Activate
   ```

   The installer does not restart DSH. If the plugin has not started, follow the troubleshooting steps in the [guide](<docs/SETUP.md>): a restart may be needed, but it is approved separately.

   If home Wi‑Fi needs a Windows Firewall rule, add it only with the owner's consent and only for the private network. The installer does not change the firewall automatically.

5. **Install the APK on the phone.**

   Allow Android to install apps from the chosen source. Turn this permission off after installation.

6. **Pair the phone with a QR code.**

   On the computer, specify the directory of the installed plugin version and check the configuration:

   ```powershell
   $plugin = '<EXACT_LOCALAPPDATA_DSHMOBILE_PLUGIN_VERSION_DIRECTORY>'
   $cli = Join-Path $plugin 'dist/cli.js'
   $config = Join-Path $env:LOCALAPPDATA 'DSHMobile/host/host.json'
   node $cli verify-config --config $config
   ```

   Then choose **one** pairing command. Read-only access to all current and future projects:

   ```powershell
   node $cli pair --config $config --read all --ttl 300 --qr
   ```

   Or read access **and running tasks** in all of these projects — only if you agree to give the phone these rights:

   ```powershell
   node $cli pair --config $config --read all --execute all --ttl 300 --qr
   ```

   In the app, tap “Scan QR code”, check the computer's address and the certificate fingerprint, then confirm pairing. The camera is used only for scanning; the code is recognized without a network and without Google services.

**To use it away from home,** install Tailscale on the computer and the phone, connect both to your Tailscale network and specify the computer's name when preparing the plugin. You do not need to open a port on your router. The alternative is [your own relay on a VPS](<docs/RELAY_DEPLOYMENT.md>).

**Upgrading:** APK 0.8.2 is signed with the same key as all previous releases and installs over them without uninstalling the app. On October 24, 2026, installing 0.8.2 over 0.8.1 was verified on a OnePlus with Android 16: pairing was kept. Verification history: on October 23, 2026, installing 0.8.1 over 0.8.0 was verified on a OnePlus CPH2747 phone: app data and pairing were kept. Earlier, installing 0.8.0 over 0.7.1 was verified on the owner's phone: chats and pairing were kept; settings are kept on such an upgrade as well. The 0.6.1 → 0.7.0 and 0.7.0 → 0.7.1 upgrades were verified on the same phone, and installing 0.7.1 over the published 0.7.0 was also verified on an Android 11 emulator. The plugin is upgraded to 0.7.2 by the installer from the new archive with the `-Upgrade -Activate` switches: the installer replaces the plugin block in the DSH profile with a placeholder, waits until the previous plugin releases its port, and only then connects the new version, and DSH picks it up on the fly. Agents keep working, and the phone briefly loses its connection. If hot reload is not enabled in DSH and the previous plugin has not released the port, the installer stops without connecting the new version and leaves the placeholder in the profile. A separately approved DSH restart is then needed, but the restart alone does not complete the upgrade: once the previous plugin has stopped, connecting the new version still has to be finished. A restart ends the agents' running turns, so do it when the agents are not running anything. In the upgrade history, plugin 0.7.1 added a new table to its database (schema 3 → 4); it does not change existing data. The plugin upgrade 0.5.1 → 0.6.0 was verified on a test copy, with the configuration, the TLS key and the rest of the profile contents preserved. The upgrade and rollback procedure is described in the [guide](<docs/SETUP.md>).

The current release pair is app 0.8.2 and plugin 0.7.2. Compatibility history (not a check of the current pair): app 0.8.1 shows new features only if the connected plugin reports them, so with plugin 0.7.0 it keeps the core features, but without the faster notification delivery and the empty-session fix; this combination was not tested separately. App 0.8.0 should work with plugin 0.7.1: the plugin keeps API v1 and the earlier contracts; this combination was not verified either. App 0.8.0 works with plugin 0.6.0 without the 0.8.0 features. App 0.7.0 was also verified on the phone with plugin 0.5.1: without the subagent indicator and with a slower first opening of a chat. App 0.6.1 is protocol-compatible with plugin 0.6.0.

## Security

- **Agent permissions stay the same.** The plugin runs inside DSH with its permissions. Project filtering is not a sandbox: an agent can do the same from the phone as from the computer. Give the right to run tasks only to trusted devices.
- **The invitation is a secret.** The QR code is single-use and valid for no more than 15 minutes. Do not publish it, send it to a chat or save it in shared logs. Pairing checks the computer's certificate, its validity period, the address and the pinned fingerprint.
- **Provider keys are not sent to the phone.** Pairing data is protected by Android Keystore and excluded from backups. The plugin's private files on Windows are accessible only to the owner of the user account.
- **Revoke a lost phone on the computer.** Signing out of the app locally deletes the pairing data from the phone but does not revoke access on the DSH side.
- **Notifications contain no reply text.** The “Answer ready” notification shows only the project and chat names. A notification about an agent question shows the text of the first question on an unlocked phone and only the chat name on the lock screen. Chat content is hidden on the lock screen.
- **The relay does not see chat contents.** It forwards encrypted traffic but sees IP addresses, timing and the amount of data transferred.

Do not expose the DSH web port to the outside and do not disable TLS verification. Details are in the [security overview](<docs/SECURITY.md>).

## Building from source

From the repository root, in PowerShell:

```powershell
cd host;  npm ci; npm ci --prefix ../relay; npm run check   # companion plugin, Node 24; the tests need the neighboring relay
cd ..\android
powershell -NoProfile -ExecutionPolicy Bypass -Command "& ../tools/android-build.ps1 -Tasks ':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug'"
```

JDK 21 is recommended; the Windows helper also supports JDK 17. You need Android SDK platform 36 and build-tools 36.0.0.

A regular release build of the APK is unsigned and cannot be installed as is. Building, packaging the plugin and signing with your own key are described in the [build guide](<docs/BUILD.md>) and the [APK release guide](<docs/ANDROID_RELEASE.md>).

## Release 0.8.2 checks

Release: app 0.8.2 (versionCode 15) and companion plugin 0.7.2; details are in the [0.8.2 release notes](https://github.com/pavel-logachev/dsh-mobile/releases/tag/v0.8.2). Plugin 0.7.2 runs in the owner's DSH, and app 0.8.2 on his phone. The checks of the previous release are in the [0.8.0 release notes](https://github.com/pavel-logachev/dsh-mobile/releases/tag/v0.8.0).

| Component | What was checked |
|---|---|
| Android 0.8.2 | On Android code 3dc0126: two full JVM runs, 357/357 each; lintDebug — 0 errors (46 warnings, 1 hint). The release helper runs assembleRelease, testDebugUnitTest and lintRelease |
| APK signature | The same certificate, SHA-256: F9080C63B49A0BDE6B74AA2ADF7E9531D6C4F305978A24D3F5D271A39F1B7B95; v2/v3 schemes, 16 KB alignment; non-debuggable, HTTPS-only |
| Plugin 0.7.2 | 282/282 with the Cordis module env; without it, 275 pass and 5 skipped (280 total). Independent re-review of the host code: GO after three blockers were fixed |
| Installer | 28/28 synthetic scenarios; not a check of installation on a phone |
| Physical phone | OnePlus, Android 16, APK 3dc0126: the sending status after 0.3–0.7 s; sends in a new and an existing chat and after more than 110 s idle; send → Home ×2 — one delivered prompt each time; the upgrade over 0.8.1 kept pairing |
| Owner's observation, October 24, 2026, around 11:40 Moscow time | App 0.8.2 + live host 0.7.2: «Ну вроде бы всё приходит, и чат открывается нужный» (“Well, everything seems to arrive, and the right chat opens”). Everyday use, no instrumented latency measurements |
| Not verified with instruments on a phone | Notification latency and tap routing with host 0.7.2; auto-retry and the “not sent” banner on a real network switch (the send button was disabled during reconnect); delivery under real Doze |
| APK permissions | INTERNET, CAMERA, POST_NOTIFICATIONS, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, ACCESS_NETWORK_STATE and the app's own signature-level receiver permission. No telemetry and no Google services |

## Limitations

- **The computer and DSH must be on.** The phone does not run the agent on its own.
- **Notifications depend on a background connection.** There is no push through Google (FCM). While notifications are on, a “DSH Mobile connected” entry is visible in the notification shade; Android may stop this connection. After the phone restarts or the app is force-stopped, open the app again.
- **Battery saving on some manufacturers' phones may delay notifications.** Push through UnifiedPush is planned.
- **Approvals of agent actions happen on the computer.** DSH does not give plugins access to them. You can answer the agent's questions from the phone if plugin 0.7.0 or later is installed on the computer.
- **The notification-delay and send-admission fixes in 0.8.2 require plugin 0.7.2.** Older version combinations do not replace a check of the current pair.
- **One pairing, one computer.** The list includes the first 100 registered projects. The full conversation history is not available without a connection to the computer.
- **Updating DSH requires a compatibility check.** The plugin checks the version from its settings, not the version actually installed. Only the verified versions listed above are supported.
- **A dropped connection does not mean the task was not sent.** The app checks its status with the computer. It resends on its own only if the network is known to have failed before the computer answered: up to three attempts with the same request ID. If sending fails, the draft is kept and the app shows a “not sent” banner with a Retry button; this path has not yet been tested on a phone with a real network switch. If delivery is still uncertain, check the result on the computer before sending the task again.

## Documentation

- [Installation, upgrade and removal](<docs/SETUP.md>)
- [Architecture](<docs/ARCHITECTURE.md>)
- [Mobile client protocol](<docs/PROTOCOL.md>)
- [Security](<docs/SECURITY.md>)
- [DSH integration](<docs/DSH_INTEGRATION.md>)
- [Deploying your own relay](<docs/RELAY_DEPLOYMENT.md>) and [its protocol](<docs/RELAY_PROTOCOL.md>)
- [Building from source](<docs/BUILD.md>)
- [Signing and releasing the Android APK](<docs/ANDROID_RELEASE.md>)
- [Contributing](<CONTRIBUTING.md>)

## Need something similar?

This project was built by [Pavel Logachev](https://github.com/pavel-logachev). I build websites, web services, Telegram bots, apps and automation for your task: I work out the task, build the solution and launch it myself. 20 years in IT: first as an engineer, then as a project manager. Tell me about your task [by email](mailto:pavel@logachev.net) or [on Telegram](https://t.me/pavel_logachev) — a 20–30 minute review is free, and you do not need a ready-made specification. Prices and other projects are on [logachev.net](https://logachev.net/) and [in my profile](https://github.com/pavel-logachev).

## License

[MIT](<LICENSE>) © 2026 Pavel Logachev. Third-party dependencies are distributed under their own licenses.
