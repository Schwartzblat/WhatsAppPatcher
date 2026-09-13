# WhatsApp Patcher

WhatsApp patcher is a program that patches WhatsApp apk.

## Features

|                    Feature                    | Availability |
|:---------------------------------------------:|:------------:|
|           Signature Verifier Bypass           |      ✔       |
|              Enable all AB tests              |      X       |
|         Keep revoked for all messages         |      ✔       |
|     Trash icon marking a revoked message      |      ✔       |
|        Disable read receipts, per chat        |      ✔       |
|      Remove Meta AI from the home screen      |      ✔       |
|        Option to save view once media         |      ✔       |
|          Profile Picture Screenshot           |      ✔       |
|            Unlock premium features            |      ✔       |
| Settings screen to switch features on and off |      ✔       |

There is support for apks, apkm and xapk files.

### Read receipts

Turning this on stops both the blue ticks and the blue microphone on voice notes
from reaching the sender, while you still see theirs. WhatsApp's own privacy
setting does both directions at once, which is why this is a patch and not just a
switch in the app.

It works by changing the *type* of receipt the app sends rather than dropping it:
WhatsApp already has a `read-self` receipt that only syncs to your own linked
devices, so your other devices still mark the chat read.

It can also apply to some chats rather than all of them. The settings screen
offers every chat, only the chats you pick, or every chat except the ones you
pick -- a blocklist and an allowlist over one list of chats, so switching between
them keeps your picks. Chats are picked from WhatsApp's own contacts and groups,
and unlike the switches this takes effect straight away, with no restart.

### Meta AI

WhatsApp puts Meta AI on the home screen in two different places, and which one
you get depends on the build and on your account, so this removes both: the
**Meta AI tab** in the bottom bar, and the **Meta AI button** above the new-chat
button on the chats screen.

Neither is hidden -- both are removed the way WhatsApp removes them itself. The
bottom bar is drawn from a list of tabs the app builds when it starts, and the
patch leaves Meta AI out of it; the button is kept in a stub the app only fills
in after asking the chat list whether it wants one, and the patch answers no,
exactly as every other tab already does.

### Settings

The patch adds a **Patcher** row to WhatsApp's own settings screen, leading to a
list of everything above grouped into sections, each with a switch. The ones the
patched app needs in order to run at all are listed but locked.

Switches are read once when the app starts, so changing one takes effect on the
next launch -- there is a button at the bottom of the screen that restarts
WhatsApp for you.

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