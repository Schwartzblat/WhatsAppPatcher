# WhatsApp Patcher

WhatsApp patcher is a program that patches WhatsApp apk.

## Features

|                     Feature                     | Availability |
|:-----------------------------------------------:|:------------:|
|            Signature Verifier Bypass            |      ✔      |
|               Enable all AB tests               |      ✔      |
|          Keep revoked for all messages          |      ✔      |
|      Trash icon marking a revoked message       |      ✔      |
| Unlimited time to delete a message for everyone |      ✔      |
|         Disable read receipts, per chat         |      ✔      |
|       Remove Meta AI from the home screen       |      ✔      |
|         Option to save view once media          |      ✔      |
|           Profile Picture Screenshot            |      ✔      |
|           Tag a group with @everyone            |      ✔      |
|  Settings screen to switch features on and off  |      ✔      |
|     Statistics for a group and its members      |      ✔      |
|     Send more group history to a new member     |      ✔      |
|    Shared members between two or more groups    |      ✔      |
|     Back up chats to your own Google Drive      |      ✔      |
|      Restore a backup without reinstalling      |      ✔      |

There is support for apks, apkm and xapk files.

## Requirements

1. Python 3.11
2. Java installed

## Installation

```bash
git clone https://github.com/Schwartzblat/WhatsAppPatcher.git

cd WhatsAppPatcher

git submodule update --init --recursive
```

Requirements installation:

```bash
pip install -r requirements.txt
```

## Usage

```bash
python main.py -p ./WhatsApp.apk -o PatchedWhatsApp.apk -g YOUR_GOOGLE_API_KEY
```

You don't have to provide a Google API key, only if you want to patch the OAuth bypass feature.

This will determinate if you could use the Google Maps features inside WhatsApp.

## Patch bundle (on-device patching)

`bundle.py` builds the signed patch bundle the on-device patcher downloads, written to
`bundle-com.whatsapp-<PATCHER_VERSION>.zip` (`-o` to change):

```bash
python bundle.py --paywall ../PatchPaywall --signing-key /outside/the/repo/bundle-signing-key.pem
```

- `--paywall`: the PatchPaywall checkout.
- `--signing-key`: the EC P-256 private key (PEM) that signs the bundle. Keep it outside the repository and
  never commit it.
- `--upload`: after building, check the bundle against the signing key's public key and upload it to
  `$PATCH_SERVER_URL/api/internal/bundles` with `$BUNDLE_UPLOAD_SECRET` (the server's
  `BUNDLE_UPLOAD_SECRET`). Set both in the environment, not on the command line.

Upload the bundle of a new `PATCHER_VERSION` before the crawler's patcher checkout is updated to it: the
server's `latest` ignores records of a patcher version that has no bundle.

## Google API Key

To get a Google API key, follow these steps:

1. Go to the [Google Cloud Console](https://console.cloud.google.com/).
2. Create a new project or select an existing one.
3. Navigate to the "APIs & Services" section.
4. Click on "Enable APIs and Services" and search for the "Maps SDK for Android" and for "Places API".
5. Enable the API for your project.
6. Go to the "Credentials" tab and click on "Create Credentials".
7. Select "API Key" from the dropdown menu.
8. Copy the generated API key and use it in the Moovit Patcher.
9. Make sure to restrict the API key to your app's package name and SHA-1 certificate fingerprint for security.

## Contributing

If you want to add patches you can open an issue and I will implement it, or you can add patches yourself and open pull
request.

## Disclaimer

For educational purpose only or something like that. I am not responsible for any misuse of this software.