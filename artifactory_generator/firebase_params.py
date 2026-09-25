from pathlib import Path

from androguard.core.apk import APK
from androguard.core.axml import ARSCParser
from stitch.apk_utils import is_bundle
from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder

from artifactory_generator.smali import CLASS_RE
from stitch.common import BUNDLE_APK_EXTRACTED_PATH, EXTRACTED_PATH


class FirebaseParamsFinder(SimpleArtifactoryFinder):
    def __init__(self, args):
        super().__init__(args)
        self.is_once = True
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        return '"ApplicationId must be set."' in class_data

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        artifacts['FIREBASE_PARAMS_CLASS_NAME'] = CLASS_RE.search(class_data).groupdict().get('name').replace('/', '.')
        resources_path = Path(self.args.temp_path) / EXTRACTED_PATH / 'resources.arsc'

        if is_bundle(self.args.apk_path):
            from stitch.apk_utils import main_apk_name
            package_name = APK(str(Path(self.args.temp_path) / BUNDLE_APK_EXTRACTED_PATH / main_apk_name)).get_package()
        else:
            package_name = APK(self.args.apk_path).get_package()
        resources = ARSCParser(resources_path.read_bytes())
        _, original_google_api_key = resources.get_string(package_name, 'google_api_key')
        artifacts['ORIGINAL_API_KEY'] = original_google_api_key
        self.is_found = True
