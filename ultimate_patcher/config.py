from pathlib import Path
import enum

APKTOOL_PATH = Path('./bin/apktool_2.11.0.jar')
UBER_APK_SIGNER_PATH = Path('./bin/uber-apk-signer-1.2.1.jar')
EXTRACTED_TEMP_DIR = 'extracted'
SMALI_GENERATOR_PATH = Path('./smali_generator')
SMALI_GENERATOR_OUTPUT_PATH = './smali_generator.apk'
SMALI_GENERATOR_SMALI_PATH = SMALI_GENERATOR_PATH / 'extracted'


class ManifestKeys(enum.StrEnum):
    EXPORTED = '{http://schemas.android.com/apk/res/android}exported'
    NAME = '{http://schemas.android.com/apk/res/android}name'
    TARGET_ACTIVITY = '{http://schemas.android.com/apk/res/android}targetActivity'


ANDROID_MANIFEST_RELEVANT_TAGS = ['activity', 'activity-alias', 'provider', 'receiver', 'service']
