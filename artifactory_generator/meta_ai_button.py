import re
from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder

from artifactory_generator.smali import CLASS_RE

METHOD_RE = re.compile(
    r'^\.method (?P<modifiers>[\w ]*?)(?P<name>[\w$]+)(?P<sig>\([^)\n]*\)[\w/$;\[]+)\n'
    r'(?P<body>.*?)^\.end method$',
    re.MULTILINE | re.DOTALL)
# Matched against a body with baksmali's .line markers and blank lines stripped,
# so a cast and the call it feeds have to be adjacent.
NOISE_RE = re.compile(r'^[ \t]*(?:\.line \d+)?[ \t]*\n', re.MULTILINE)
# The gate is asked on an object fetched lazily and cast just before the call:
# an obfuscated class (X/...), which is what keeps the androidx Fragment's own
# ()Z check, asked earlier in the same method, out of it.
GATE_CALL_RE = re.compile(
    r'check-cast (?P<reg>[vp]\d+), L(?P<cls>X/[\w$]+);\n'
    r'\s*invoke-virtual \{(?P=reg)\}, L(?P=cls);->(?P<method>[\w$]+)\(\)Z')
GATE_SIG = '()Z'


class MetaAiButtonFinder(SimpleArtifactoryFinder):
    """Finds the gate for the Meta AI button, and the calls tab's copy of it.

    The button has two forms -- a round fab carrying the Meta AI ring, and an
    extended pill with text beside it -- and the home screen's fab controller
    picks between them. *Whether* the chats screen shows either is one predicate
    on a class of its own, consulted before the ring drawable, the ring resource
    id, the pill and a tap. Returning false is the state of every account whose
    Meta AI button is off, so it needs no new handling anywhere.

    The gate is found by where it is asked. ``ConversationsFragment`` keeps its
    name, and its secondary fab drawable -- the only
    ``(Landroid/view/View;)Landroid/graphics/drawable/Drawable;`` it declares --
    casts the gate's owner and asks it straight away; that cast-and-call is the
    one on an obfuscated class in the method. Until 2.26.41 the gate was a
    one-method class reading WhatsApp's Settings > Chats switch
    (``bonsai_meta_ai_button_setting_enabled``), and this finder was anchored on
    that key. 2.26.42 retired the switch -- a daily cron now deletes the key --
    and moved the decision to ``X.1QX.A06``, one of ten predicates on a class
    that reads availability and an A/B flag instead. The drawable asks it at the
    same place, so this rule resolves both: ``X.16t``, ``X.17f``, ``X.118``,
    ``X.12O``, ``X.13z``, ``X.1LK`` and ``X.1LE`` before, ``X.1QX`` on 2.26.42.71,
    on every build examined from 2.26.17.72.

    The calls tab shows the same button but asks the question itself, with an
    inlined copy of the gate's check, so it survives a hook on the shared gate:
    without its own hook the button does not go away, it moves one tab over. It
    is the one ``()Z`` in ``CallsHistoryFragment`` that casts to the gate's
    class, which is why both are one finder -- the calls gate is only knowable
    once the chats gate is. Its name is an output: ``A0V``, ``A0X``, ``A0Y``,
    ``A0Z``, ``A0W``, ``A0c`` across the same builds.

    Hooking the chat list's own "do you want the pill" gate instead was the
    earlier mistake. It is only one of the four callers, so the round fab -- the
    form most accounts get -- was left in place, and on a device where the pill
    was never offered the patch looked installed and did nothing. The class name
    is read with the shared ``CLASS_RE``: stitch's own made ``X.1LK`` into ``K``.

    Files arrive in filesystem order, so the calls fragment may come first; its
    ``()Z`` methods are kept and examined once the gate's class is known.
    """

    CHATS_CLASS = 'com/whatsapp/conversationslist/ConversationsFragment'
    CALLS_CLASS = 'com/whatsapp/calling/ui/callhistory/view/CallsHistoryFragment'
    RING_SIG = '(Landroid/view/View;)Landroid/graphics/drawable/Drawable;'

    def __init__(self, args):
        super().__init__(args)
        self.is_once = True
        self.is_found = False
        self._gate_class = None
        self._calls_predicates = None

    def _class_name(self, class_data: str):
        match = CLASS_RE.search(class_data)
        return match.groupdict().get('name') if match is not None else None

    def class_filter(self, class_data: str) -> bool:
        return self._class_name(class_data) in (self.CHATS_CLASS, self.CALLS_CLASS)

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        if self._class_name(class_data) == self.CHATS_CLASS:
            self._extract_gate(artifacts, class_data)
        else:
            self._calls_predicates = [method for method in METHOD_RE.finditer(class_data)
                                      if method.group('sig') == GATE_SIG]
        if self._gate_class is not None and self._calls_predicates is not None:
            self._extract_calls_gate(artifacts)

    def _extract_gate(self, artifacts: dict, class_data: str) -> None:
        rings = [method for method in METHOD_RE.finditer(class_data) if method.group('sig') == self.RING_SIG]
        if len(rings) != 1:
            return
        calls = list(GATE_CALL_RE.finditer(NOISE_RE.sub('', rings[0].group('body'))))
        if len(calls) != 1:
            return
        self._gate_class = calls[0].group('cls')
        artifacts['META_AI_BUTTON_GATE_CLASS_NAME'] = self._gate_class.replace('/', '.')
        artifacts['META_AI_BUTTON_GATE_METHOD_NAME'] = calls[0].group('method')
        artifacts['META_AI_BUTTON_GATE_METHOD_SIG'] = GATE_SIG

    def _extract_calls_gate(self, artifacts: dict) -> None:
        casts_gate = re.compile(r'check-cast [vp]\d+, L%s;' % re.escape(self._gate_class))
        gates = [method for method in self._calls_predicates if casts_gate.search(method.group('body'))]
        if len(gates) != 1:
            return
        artifacts['META_AI_CALLS_GATE_CLASS_NAME'] = self.CALLS_CLASS.replace('/', '.')
        artifacts['META_AI_CALLS_GATE_METHOD_NAME'] = gates[0].group('name')
        artifacts['META_AI_CALLS_GATE_METHOD_SIG'] = GATE_SIG
        self.is_found = True
