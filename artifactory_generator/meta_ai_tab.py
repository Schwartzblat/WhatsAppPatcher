import re
from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder, CLASS_NAME_RE


class MetaAiTabFinder(SimpleArtifactoryFinder):
    """Finds the home screen's tab list, and which of its entries is Meta AI.

    The bottom bar is not a menu resource: WhatsApp builds an ordered list of
    tab ids and the bar is drawn from it, so a tab the list does not contain
    does not exist. Which tabs are in it already varies per account -- Updates
    drops out under a privacy setting, the last slot is Meta AI or one of two
    others depending on gating -- so removing an id is a shape the screen is
    built to take, not a state invented here.

    Two things have to be resolved, and neither may be written down:

    1. **Which id is Meta AI.** The AI tab's fragment is
       ``com/whatsapp/aihub/metaai/product/ui/AiFragmentBase`` -- an
       unobfuscated package -- and the tab it belongs to is the one thing it
       reports about itself: a single no-argument ``()I`` that returns a
       constant. The interface method's *name* is obfuscated (``B4Q`` here), so
       it is taken by shape instead, which is unambiguous because the class
       declares exactly one ``()I``. ``AiTabHostFragment`` extends this class
       and inherits the answer.

    2. **Where the list is built.** Given the id, the builder is the only
       method in the app returning ``()Ljava/util/ArrayList;`` that constructs
       the list, boxes ints into it, and mentions that id -- one match against
       four candidates of that shape on the builds examined. Anchoring on the
       id rather than on a name is what keeps this finder generic: the class
       holding it was ``X.0K5`` here and moves like every other.

    Files arrive in filesystem order, so the id may be resolved after some
    candidates have already been offered. The candidates are kept -- there are
    single digits of them, and only the bodies of methods of that exact
    signature -- and re-examined once the id is known.

    Verified to resolve both, and nothing else, on 2.26.29.74, 2.26.33.76 and
    2.26.36.71. Earlier builds have no Meta AI tab and no ``AiFragmentBase``,
    so the finder correctly does not fire on them.
    """

    FRAGMENT_CLASS = 'com/whatsapp/aihub/metaai/product/ui/AiFragmentBase'
    TABS_SIG = '()Ljava/util/ArrayList;'

    METHOD_RE = re.compile(
        r'^\.method (?P<modifiers>[\w ]*?)(?P<name>[\w$]+)(?P<sig>\([^)\n]*\)[\w/$;\[]+)\n'
        r'(?P<body>.*?)^\.end method$',
        re.MULTILINE | re.DOTALL)
    # Matched against a body with baksmali's .line markers and blank lines
    # stripped, so the constant and the return it feeds have to be adjacent --
    # which is what makes this "returns a constant" rather than "mentions one".
    NOISE_RE = re.compile(r'^[ \t]*(?:\.line \d+)?[ \t]*\n', re.MULTILINE)
    TAB_ID_RE = re.compile(r'^\s*const(?:/4|/16|/high16)? (?P<reg>[vp]\d+), (?P<id>0x[0-9a-f]+)\s*\n'
                           r'\s*return (?P=reg)\s*$', re.MULTILINE)
    NEW_LIST_RE = re.compile(r'new-instance [vp]\d+, Ljava/util/ArrayList;')
    BOX_RE = re.compile(r'invoke-static \{[vp]\d+\}, Ljava/lang/Integer;->valueOf\(I\)Ljava/lang/Integer;')
    ADD_RE = re.compile(r'->add\(Ljava/lang/Object;\)Z')

    def __init__(self, args):
        super().__init__(args)
        self.is_once = True
        self.is_found = False
        self._tab_id = None
        self._candidates = []

    def class_filter(self, class_data: str) -> bool:
        return self._is_ai_fragment(class_data) or self.TABS_SIG in class_data

    def _is_ai_fragment(self, class_data: str) -> bool:
        match = CLASS_NAME_RE.match(class_data)
        return match is not None and match.groupdict().get('name') == self.FRAGMENT_CLASS

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        if self._is_ai_fragment(class_data):
            self._extract_tab_id(class_data)
        else:
            self._collect_candidates(class_data)
        if self._tab_id is None:
            return
        self._extract_tabs_method(artifacts)

    def _extract_tab_id(self, class_data: str) -> None:
        ids = [self.TAB_ID_RE.search(self.NOISE_RE.sub('', method.group('body')))
               for method in self.METHOD_RE.finditer(class_data)
               if method.group('sig') == '()I']
        ids = [match.group('id') for match in ids if match is not None]
        if len(ids) != 1:
            return
        self._tab_id = ids[0]

    def _collect_candidates(self, class_data: str) -> None:
        class_match = CLASS_NAME_RE.match(class_data)
        if class_match is None:
            return
        for method in self.METHOD_RE.finditer(class_data):
            body = method.group('body')
            if (method.group('sig') == self.TABS_SIG
                    and self.NEW_LIST_RE.search(body)
                    and self.BOX_RE.search(body)
                    and self.ADD_RE.search(body)):
                self._candidates.append(
                    (class_match.groupdict().get('name'), method.group('name'), body))

    def _extract_tabs_method(self, artifacts: dict) -> None:
        loads_tab_id = re.compile(r'const(?:/4|/16|/high16)? [vp]\d+, %s\b' % re.escape(self._tab_id))
        builders = [candidate for candidate in self._candidates if loads_tab_id.search(candidate[2])]
        if len(builders) != 1:
            return
        class_name, method_name, _ = builders[0]
        artifacts['META_AI_TAB_ID'] = str(int(self._tab_id, 16))
        artifacts['META_AI_TABS_CLASS_NAME'] = class_name.replace('/', '.')
        artifacts['META_AI_TABS_METHOD_NAME'] = method_name
        artifacts['META_AI_TABS_METHOD_SIG'] = self.TABS_SIG
        self.is_found = True
