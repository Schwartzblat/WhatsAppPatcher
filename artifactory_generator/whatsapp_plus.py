import re
from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder, CLASS_NAME_RE


class WhatsAppPlusFinder(SimpleArtifactoryFinder):
    WHATSAPP_PLUS_RE = re.compile(
        r'\.method public ?(?P<method_name>\w+)(?P<sig>\(L.*Z)(?:\n[^\n]*){3,4}[^\n]ConcurrentHashMap')

    def __init__(self, args):
        super().__init__(args)
        self.is_once = True
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        return '"PromoEligibilityManager/refreshEligibility: promo eligibility disabled"' in class_data

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        matches = list(self.WHATSAPP_PLUS_RE.finditer(class_data))
        if len(matches) != 1:
            return
        artifacts['WHATSAPP_PLUS_CLASS_NAME'] = CLASS_NAME_RE.match(class_data).groupdict().get('name').replace('/',
                                                                                                                '.')
        artifacts['WHATSAPP_PLUS_METHOD_NAME'] = matches[0].groupdict().get('method_name')
        artifacts['WHATSAPP_PLUS_METHOD_SIG'] = matches[0].groupdict().get('sig')
        self.is_found = True
