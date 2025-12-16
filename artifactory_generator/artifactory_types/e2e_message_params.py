import re

from artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder, CLASS_NAME_RE


class E2EMessageParams(SimpleArtifactoryFinder):

    WANTED_FIELDS = {
        'EDITED_VERSION_FIELD': 'editedVersion',
    }
    FIELD_RE = r'=\"\n(?:.*\n)*?\s*iget(?:-\w+)? \w+, \w+, (?P<class>[^;]*;)->(?P<field>\w+).*'

    def __init__(self, args):
        super().__init__(args)
        self.is_once = True
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        return '"ParseE2EMessageParams(e2eMessage="' in class_data

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        artifacts['E2EMESSAGE_PARAMS_CLASS'] = CLASS_NAME_RE.match(class_data).groupdict().get('name').replace('/', '.')
        for field_key, field_name in self.WANTED_FIELDS.items():
            field_re = re.compile(field_name + self.FIELD_RE)
            match = field_re.search(class_data)
            if match:
                artifacts[field_key] = match.groupdict().get('field')
        self.is_found = True
