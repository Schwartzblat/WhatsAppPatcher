from pathlib import Path

PATCHER_VERSION = "3.0.3"
PACKAGE = "com.whatsapp"
MODULE_PROVIDER = "com.smali_generator.InitProvider"
# Stitch writes this into the target's manifest as the provider's android:name,
# and the paywall module generates a class of that name from PAYWALL_PROVIDER_CLASS.
# Both are derived from this one constant so they cannot drift: a manifest naming
# a class that is not in the dex installs fine and dies at launch.
PAYWALL_PROVIDER = "com.paywall.InitProviderPaywallWhatsApp"
ARTIFACTS_ASSET = "assets/patcher/artifactory.json"
FEATURES_PATH = Path(__file__).parent / "smali_generator/app/src/main/assets/patcher/features.json"
