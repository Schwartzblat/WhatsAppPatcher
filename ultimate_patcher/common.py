from pathlib import Path
import enum

APKTOOL_PATH = Path('./bin/apktool_2.12.1.jar')
UBER_APK_SIGNER_PATH = Path('./bin/uber-apk-signer-1.2.1.jar')
EXTRACTED_PATH = 'extracted'
BUNDLE_APK_EXTRACTED_PATH = Path('bundle')
SMALI_GENERATOR_TEMP_PATH = './smali_generator'
SMALI_EXTRACTED_PATH = './smali_extracted'
SMALI_GENERATOR_OUTPUT_PATH = './smali_generator.apk'


class ManifestKeys(enum.StrEnum):
    EXPORTED = '{http://schemas.android.com/apk/res/android}exported'
    NAME = '{http://schemas.android.com/apk/res/android}name'
    TARGET_ACTIVITY = '{http://schemas.android.com/apk/res/android}targetActivity'


ANDROID_MANIFEST_RELEVANT_TAGS = ['activity', 'activity-alias', 'provider', 'receiver', 'service']
