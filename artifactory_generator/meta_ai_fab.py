import re
from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder, CLASS_NAME_RE


class MetaAiFabFinder(SimpleArtifactoryFinder):
    """Finds the chat list's gate for the Meta AI button.

    The Meta AI button on the home screen is a ``com.whatsapp.home.
    ExtendedMiniFab`` -- the small pill above the new-chat FAB, whose own
    accessors call it ``getMetaAiRingSmallRes``. It lives in a ViewStub owned by
    the shared home-screen FAB controller, and the controller inflates that stub
    only after asking the current tab's fragment one boolean question: *do you
    want the extended mini fab?* Every other tab (Updates, Calls, Communities)
    answers a constant ``false`` and never gets one.

    That question is what this finder pins, because answering it ``false`` is
    the state WhatsApp already produces for every account it does not offer Meta
    AI to: the stub is never inflated, so the button is absent rather than
    hidden, and the tab-switch path that hands the inflated view back to the
    fragment is skipped too (the holder reports visibility ``GONE`` when its
    view is null). Nothing downstream is taught a new state.

    Both anchors are names obfuscation cannot touch:

    1. the fragment is ``com/whatsapp/conversationslist/ConversationsFragment``,
       an unobfuscated package. Its subclasses -- archived, locked and the other
       folder lists -- do not override the gate, so hooking the declaration here
       covers them as well, which is what stops the button reappearing on those
       screens;
    2. the Meta AI fab delegate is the one class carrying the literal
       ``"WAAI.FAB"``, the presentation-source tag it stamps on the intent it
       launches. It reaches the server as analytics, so it cannot be renamed. It
       matched exactly one class in every build examined, while the class itself
       was renamed in all of them: ``X.1EW``, ``X.1Hu``, ``X.1SP``, ``X.1YX``.

    The gate is then the only ``public ()Z`` method in the fragment that reaches
    into that delegate *without calling anything on it*. The fragment holds a
    second such method -- the fab's long-press handler, which also returns a
    boolean and also reaches the delegate -- and the two are told apart by what
    a gate is: a question, not an action. The handler launches Meta AI, so it
    invokes an instance method on the delegate; the gate only reads flags and
    fields off it. Deriving the delegate from a string literal rather than
    naming it keeps every obfuscated name in this finder an output.

    Verified to resolve the gate, and nothing else, on 2.26.17.72, 2.26.27.85,
    2.26.29.74 and 2.26.33.76.
    """

    FRAGMENT_CLASS = 'com/whatsapp/conversationslist/ConversationsFragment'
    ORIGIN_RE = re.compile(r'const-string(?:/jumbo)? [vp]\d+, "WAAI\.FAB"')

    METHOD_RE = re.compile(
        r'^\.method (?P<modifiers>[\w ]*?)(?P<name>[\w$]+)(?P<sig>\([^)\n]*\)[\w/$;\[]+)\n'
        r'(?P<body>.*?)^\.end method$',
        re.MULTILINE | re.DOTALL)
    GATE_SIG = '()Z'

    def __init__(self, args):
        super().__init__(args)
        self.is_once = True
        self.is_found = False
        # The two halves arrive in filesystem order, so either may be first and
        # the fragment has to be kept until the delegate names itself.
        self._delegates = set()
        self._fragment = None

    def class_filter(self, class_data: str) -> bool:
        return self._is_fragment(class_data) or self.ORIGIN_RE.search(class_data) is not None

    def _is_fragment(self, class_data: str) -> bool:
        match = CLASS_NAME_RE.match(class_data)
        return match is not None and match.groupdict().get('name') == self.FRAGMENT_CLASS

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        if self._is_fragment(class_data):
            self._fragment = class_data
        else:
            match = CLASS_NAME_RE.match(class_data)
            if match is None:
                return
            self._delegates.add(match.groupdict().get('name'))
        if self._fragment is None or len(self._delegates) != 1:
            return
        self._extract_gate(artifacts)

    def _extract_gate(self, artifacts: dict) -> None:
        reference = 'L%s;' % next(iter(self._delegates))
        # Field reads and static calls are a question; an instance call is the
        # long-press handler launching Meta AI.
        acts_on_delegate = re.compile(
            r'invoke-(?:virtual|direct|interface)(?:/range)? [^\n]*%s->' % re.escape(reference))
        gates = [method for method in self.METHOD_RE.finditer(self._fragment)
                 if method.group('sig') == self.GATE_SIG
                 and 'public' in method.group('modifiers')
                 and reference in method.group('body')
                 and acts_on_delegate.search(method.group('body')) is None]
        if len(gates) != 1:
            return
        artifacts['META_AI_FAB_GATE_CLASS_NAME'] = self.FRAGMENT_CLASS.replace('/', '.')
        artifacts['META_AI_FAB_GATE_METHOD_NAME'] = gates[0].group('name')
        artifacts['META_AI_FAB_GATE_METHOD_SIG'] = self.GATE_SIG
        self.is_found = True
