# SambaS3 Privacy Policy

Effective date: September 9, 2026

SambaS3 is an open-source PS3 emulator frontend for Android maintained by the JICA98 project. This policy describes the information handled by the app.

## Information SambaS3 handles

SambaS3 does not require an online account. It does not include advertising or analytics SDKs, sell personal information, or send game files, save states, screenshots, profile names, logs, or performance captures to the developer.

The app stores the following information locally on your device when you use the related features:

- folders and document URIs you select for games, firmware, imports, exports, and screenshots;
- emulator settings, controller mappings, local PS3 profile names, game metadata, save states, shader and PPU caches, and compatibility or crash records;
- device, Android, GPU, driver, memory, thermal, battery, and emulator performance information in local diagnostic logs or captures.

Android may back up eligible app data according to your device and Google backup settings. You can disable device backup in Android settings.

## Network access and sharing

The Google Play build includes its emulator core and supported GPU-driver packages. It does not download executable code. SambaS3 may retrieve public RPCS3 game-patch metadata from `rpcs3.net` when you use patch features.

Logs leave the device only when you explicitly choose a share or export action and select a destination in Android's system share interface. Any destination you choose has its own privacy practices.

## Permissions

SambaS3 uses Android's system file picker to access only folders and documents you select. Notification permission is used for visible emulation, installation, and compilation progress. Foreground-service permissions keep user-started emulation and compilation work visible and stoppable. Controller, vibration, and USB access support game input and connected devices.

## Retention and deletion

Local information remains until you remove it through SambaS3, clear the app's storage, uninstall the app, or delete exported files yourself. Diagnostic log retention is bounded by the app's log rotation. Because the project does not operate an account service or receive app data automatically, there is no server-side account data to delete.

## Children

SambaS3 is not directed to children and does not knowingly collect personal information from children. Game content is supplied by the user and may have its own age rating.

## Changes and contact

Changes to this policy will be published in this repository with a revised effective date. Questions or privacy requests can be filed through the project's [GitHub issue tracker](https://github.com/jica98/samba-s3/issues).
