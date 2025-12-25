from artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder, CLASS_NAME_RE


class FMessage(SimpleArtifactoryFinder):

    def __init__(self, args):
        super().__init__(args)
        self.is_once = True
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        return '\"FMessage/getSenderUserJid/key.id=\"' in class_data

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        artifacts['FMESSAGE_CLASS'] = CLASS_NAME_RE.match(class_data).groupdict().get('name').replace('/', '.')
        self.is_found = True
