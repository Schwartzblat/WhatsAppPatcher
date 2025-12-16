from artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder, CLASS_NAME_RE


class MessageSecret(SimpleArtifactoryFinder):

    def __init__(self, args):
        super().__init__(args)
        self.is_once = True
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        return '"Length too large: "' in class_data and 'serialVersionUID' in class_data

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        artifacts['MESSAGE_SECRET_CLASS'] = CLASS_NAME_RE.match(class_data).groupdict().get('name').replace('/', '.')
        self.is_found = True
