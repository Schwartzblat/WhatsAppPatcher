import re
from artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder, CLASS_NAME_RE


class IncomingMessageManagerFinder(SimpleArtifactoryFinder):
    INCOMING_MANAGER_PROTOBUF_RE = re.compile(r'\.method public final (?P<method_name>\w+)(?P<sig>\(L[^;]+;L[^;]+;L[^;]+;L[^;]+;\[B\)V)\n')

    def __init__(self, args):
        super().__init__(args)
        self.is_once = True
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        return '"IncomingMessageManager/notifyIncomingFMessageBuilt "' in class_data

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        matches = list(self.INCOMING_MANAGER_PROTOBUF_RE.finditer(class_data))
        if len(matches) != 1:
            return
        artifacts['INCOMING_MANAGER_CLASS_NAME'] = CLASS_NAME_RE.match(class_data).groupdict().get('name').replace('/', '.')
        artifacts['INCOMING_MANAGER_METHOD_NAME'] = matches[0].groupdict().get('method_name')
        artifacts['INCOMING_MANAGER_METHOD_SIG'] = matches[0].groupdict().get('sig')
        self.is_found = True
