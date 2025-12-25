from abc import abstractmethod
import re

CLASS_NAME_RE = re.compile(r'\.class public.*L(?P<name>[\w/]+)')


class SimpleArtifactoryFinder:
    def __init__(self, args):
        self.args = args
        self.is_once = True
        self.is_found = False

    @abstractmethod
    def class_filter(self, class_data: str) -> bool:
        pass

    @abstractmethod
    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        pass
