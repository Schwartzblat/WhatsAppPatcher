import os
import zipfile
from pathlib import Path

from stitch.apk_utils import is_bundle
from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder
from stitch.common import EXTRACTED_PATH, BUNDLE_APK_EXTRACTED_PATH


class DexCopier(SimpleArtifactoryFinder):

    def __init__(self, args):
        super().__init__(args)
        self.is_once = True
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        return True

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        temp_path = Path(self.args.temp_path)
        apk_path = self.args.apk_path
        if is_bundle(self.args.apk_path):
            from stitch.apk_utils import main_apk_name
            apk_path = temp_path / BUNDLE_APK_EXTRACTED_PATH / main_apk_name
        zipfile.ZipFile(apk_path).extract(
            'classes.dex',
            temp_path / EXTRACTED_PATH
        )
        new_assets_path = temp_path / EXTRACTED_PATH / 'assets' / 'smali_generator'
        os.makedirs(new_assets_path, exist_ok=True)
        os.rename(temp_path / EXTRACTED_PATH / 'classes.dex',
                  new_assets_path / 'classes.dex.bin')
        self.is_found = True
