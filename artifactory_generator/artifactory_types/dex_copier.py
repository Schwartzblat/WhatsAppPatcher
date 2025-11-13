import os
import zipfile
from pathlib import Path
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
        temp_path = Path(self.args.temp_path)
        zipfile.ZipFile(self.args.apk_path).extract(
            'classes.dex',
            temp_path / EXTRACTED_PATH
        )
        os.rename(temp_path / EXTRACTED_PATH / 'classes.dex',
                  temp_path / EXTRACTED_PATH / 'classes69.dex')
        self.is_found = True
