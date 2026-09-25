import re
from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder

from artifactory_generator.smali import CLASS_RE

# The setting WhatsApp's own Settings > Chats screen writes for its Meta AI
# button. It is a SharedPreferences key, so renaming it would strand the stored
# value of every user who has ever touched that switch: it does not move.
BUTTON_PREF = 'bonsai_meta_ai_button_setting_enabled'
PREF_RE = re.compile(r'const-string(?:/jumbo)? [vp]\d+, "%s"' % BUTTON_PREF)
GET_BOOLEAN_RE = re.compile(r'->getBoolean\(Ljava/lang/String;Z\)Z')

METHOD_RE = re.compile(
    r'^\.method (?P<modifiers>[\w ]*?)(?P<name>[\w$]+)(?P<sig>\([^)\n]*\)[\w/$;\[]+)\n'
    r'(?P<body>.*?)^\.end method$',
    re.MULTILINE | re.DOTALL)
GATE_SIG = '()Z'


def _reads_the_pref(method) -> bool:
    body = method.group('body')
    return (method.group('sig') == GATE_SIG
            and PREF_RE.search(body) is not None
            and GET_BOOLEAN_RE.search(body) is not None)


class MetaAiButtonFinder(SimpleArtifactoryFinder):
    """Finds the gate for the Meta AI button on the chats screen.

    The button has two forms -- a round fab carrying the Meta AI ring, and an
    extended pill with text beside it -- and the home screen's fab controller
    picks between them. Which one an account sees is decided elsewhere, but
    *whether* it sees either is decided here: this one predicate is consulted by
    the chat list before it hands out the ring drawable, before it hands out the
    ring resource id, before it answers whether it wants the pill, and before it
    acts on a tap. Returning false is the state of every account whose Meta AI
    button is off, so it needs no new handling anywhere.

    Anchoring on the chat list's own methods instead was the earlier mistake and
    is what this replaces. The pill's gate is only one of the four callers, so
    hooking it left the round fab -- the form most accounts get -- untouched,
    and on a device where the pill was never offered the patch looked installed
    and did nothing.

    The class is found by what it is for: it declares exactly one method, a
    ``()Z`` that reads {BUTTON_PREF} out of SharedPreferences. Nothing else in
    the app has that shape, while the class itself was renamed in every build
    examined -- ``X.16t``, ``X.17f``, ``X.118``, ``X.12O``, ``X.13z``, ``X.1LK``.
    That last one is why the name is read with the shared ``CLASS_RE``: stitch's
    own regex made it ``K``.

    Deliberately not hooked: WhatsApp's own Settings > Chats screen, which reads
    the same key directly to draw its switch. It keeps showing the user's real
    setting rather than one this patch invented.

    Verified to resolve the gate, and nothing else, on 2.26.17.72, 2.26.27.85,
    2.26.29.74, 2.26.33.76, 2.26.36.71 and 2.26.37.74.
    """

    def __init__(self, args):
        super().__init__(args)
        self.is_once = True
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        return PREF_RE.search(class_data) is not None

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        methods = list(METHOD_RE.finditer(class_data))
        # One method and nothing else: the class exists to answer this. The
        # chat list and the settings screen read the same key inside much larger
        # classes, and neither is what should be hooked.
        if len(methods) != 1 or not _reads_the_pref(methods[0]):
            return
        class_match = CLASS_RE.search(class_data)
        if class_match is None:
            return
        artifacts['META_AI_BUTTON_GATE_CLASS_NAME'] = class_match.groupdict().get('name').replace('/', '.')
        artifacts['META_AI_BUTTON_GATE_METHOD_NAME'] = methods[0].group('name')
        artifacts['META_AI_BUTTON_GATE_METHOD_SIG'] = GATE_SIG
        self.is_found = True


class MetaAiCallsButtonFinder(SimpleArtifactoryFinder):
    """Finds the gate for the Meta AI button on the calls screen.

    The calls tab shows the same button from the same setting, but asks the
    question itself rather than through the shared gate
    {MetaAiButtonFinder} pins: it reads the two fields off that class
    inline and pairs them with a different availability check. So it survives a
    hook on the shared gate, and without this the button does not go away -- it
    moves one tab over.

    Anchored on the same SharedPreferences key, inside a class whose name is in
    an unobfuscated package. Within it the method is the only ``()Z`` that reads
    the key, which is how its name is left an output: it was ``A0V``, ``A0X``,
    ``A0Y`` and ``A0Z`` across four builds.

    Verified on 2.26.27.85, 2.26.29.74, 2.26.33.76 and 2.26.36.71. 2.26.17.72
    has no Meta AI button on the calls tab and nothing here to find, so the
    finder correctly does not fire on it.
    """

    FRAGMENT_CLASS = 'com/whatsapp/calling/ui/callhistory/view/CallsHistoryFragment'

    def __init__(self, args):
        super().__init__(args)
        self.is_once = True
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        if PREF_RE.search(class_data) is None:
            return False
        match = CLASS_RE.search(class_data)
        return match is not None and match.groupdict().get('name') == self.FRAGMENT_CLASS

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        gates = [method for method in METHOD_RE.finditer(class_data) if _reads_the_pref(method)]
        if len(gates) != 1:
            return
        artifacts['META_AI_CALLS_GATE_CLASS_NAME'] = self.FRAGMENT_CLASS.replace('/', '.')
        artifacts['META_AI_CALLS_GATE_METHOD_NAME'] = gates[0].group('name')
        artifacts['META_AI_CALLS_GATE_METHOD_SIG'] = GATE_SIG
        self.is_found = True
