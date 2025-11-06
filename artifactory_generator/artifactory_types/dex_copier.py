import os
import zipfile

from artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder
from ultimate_patcher.common import EXTRACTED_PATH


class DexCopier(SimpleArtifactoryFinder):

    def __init__(self, args):
        super().__init__(args)
        self.is_once = True
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        return True

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        zipfile.ZipFile(self.args.apk_path).extract(
            'classes.dex',
            self.args.temp_path / EXTRACTED_PATH
        )
        os.rename(self.args.temp_path / EXTRACTED_PATH / 'classes.dex',
                  self.args.temp_path / EXTRACTED_PATH / 'classes69.dex')
        self.is_found = True
