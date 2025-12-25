# WhatsApp Patcher

WhatsApp patcher is a program that patches WhatsApp apk.

## Features

|            Feature             | Availability |
|:------------------------------:|:------------:|
|   Signature Verifier Bypass    |      ✔       |
|      Enable all AB tests       |      X       |
| Keep revoked for all messages  |      ✔       |
|     Disable read receipts      |      x       |
| Option to save view once media |      ✔       |
|   Profile Picture Screenshot   |      ✔       |

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
python whatsapp_patcher/main.py -p ./WhatsApp.apk -o PatchedWhatsApp.apk -g YOUR_GOOGLE_API_KEY
```

You don't have to provide a Google API key, only if you want to patch the OAuth bypass feature.

This will determinate if you could use the Google Maps features inside WhatsApp.

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