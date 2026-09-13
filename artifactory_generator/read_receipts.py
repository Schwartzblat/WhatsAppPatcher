import re
from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder, CLASS_NAME_RE


class ReadReceiptsFinder(SimpleArtifactoryFinder):
    """Finds the two methods that decide whether a receipt is sent to the sender.

    WhatsApp does not choose between "send a receipt" and "send nothing". It
    always sends a ``receipt`` stanza and chooses its *type*: ``read`` goes to
    the sender and turns the ticks blue, ``read-self`` goes only to your own
    linked devices so they can mark the chat read. Voice notes work the same
    way with ``played`` and ``played-self``. Both choices are made in one
    class, which its own log strings call ``ReadReceiptUtils``:

    * a ``(Jid, boolean)String`` method returning ``"read"`` or ``"read-self"``;
    * a ``(Jid)boolean`` method the played-receipt job asks before it picks
      ``"played"`` over ``"played-self"``.

    Hooking these two rather than the jobs that send the stanza is what makes
    the per-chat version of this feature possible: both take the chat's Jid,
    and returning the self-variant is a path WhatsApp already takes for
    itself, so nothing downstream has to be taught a new state.

    The class is anchored on ``ReadReceiptUtils/``, the prefix of its own log
    messages -- the one name here obfuscation cannot touch. It matched exactly
    one class in every build examined, while the class itself was renamed in
    all four: ``X.0gN``, ``X.0lo``, ``X.1AB``, ``X.1Cx``.

    Both methods are then pinned by shape:

    1. the receipt-type method is the only ``public`` one whose signature is
       ``(L...;Z)Ljava/lang/String;`` and whose body holds both the ``"read"``
       and ``"read-self"`` literals. Those literals are wire-format values, so
       they cannot be renamed either;
    2. the played gate is the only ``public`` ``(L...;)Z`` method that calls at
       least two of the class's own private ``(L...;)Z`` helpers -- the pair
       the receipt-type method calls, which is how they are identified.
       Deriving them from an already-pinned method keeps every name in this
       finder an output. Within the class the rule is exact: those two helpers
       are called from the receipt-type method and the played gate, and from
       nowhere else.

    The same finder also resolves how to read a chat's Jid as a string, which
    the per-chat lists are keyed on. ``Jid.toString()`` is not it: it returns
    ``getObfuscatedString()``, whose name says exactly what it is meant to
    become even though today it happens to delegate to the raw value. The
    accessor wanted is the one that builds the string from the ``user`` field
    and the server, found by that shape rather than by name.

    Verified to resolve all three, and nothing else, on 2.26.17.72, 2.26.27.85,
    2.26.29.74 and 2.26.33.76.
    """

    ANCHOR = 'ReadReceiptUtils/'
    JID_CLASS = 'com/whatsapp/infra/core/jid/Jid'

    METHOD_RE = re.compile(
        r'^\.method (?P<modifiers>[\w ]*?)(?P<name>[\w$]+)(?P<sig>\([^)\n]*\)[\w/$;\[]+)\n'
        r'(?P<body>.*?)^\.end method$',
        re.MULTILINE | re.DOTALL)
    RECEIPT_TYPE_SIG_RE = re.compile(r'^\(L[\w/$]+;Z\)Ljava/lang/String;$')
    PLAYED_GATE_SIG_RE = re.compile(r'^\(L[\w/$]+;\)Z$')
    READ_RE = re.compile(r'const-string(?:/jumbo)? [vp]\d+, "read"')
    READ_SELF_RE = re.compile(r'const-string(?:/jumbo)? [vp]\d+, "read-self"')

    RAW_STRING_SIG = '()Ljava/lang/String;'
    USER_FIELD_RE = re.compile(r'->user:Ljava/lang/String;')
    GET_SERVER_RE = re.compile(r'->getServer\(\)Ljava/lang/String;')

    MIN_SHARED_HELPERS = 2

    def __init__(self, args):
        super().__init__(args)
        self.is_once = True
        self.is_found = False
        self._found_utils = False
        self._found_jid = False

    def class_filter(self, class_data: str) -> bool:
        return self.ANCHOR in class_data or self._is_jid_class(class_data)

    def _is_jid_class(self, class_data: str) -> bool:
        match = CLASS_NAME_RE.match(class_data)
        return match is not None and match.groupdict().get('name') == self.JID_CLASS

    @staticmethod
    def _calls(body: str, name: str) -> bool:
        return re.search(r'->%s\(L[\w/$]+;\)Z' % re.escape(name), body) is not None

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        if self._is_jid_class(class_data):
            self._extract_jid(artifacts, class_data)
        else:
            self._extract_receipt_methods(artifacts, class_data)
        self.is_found = self._found_utils and self._found_jid

    def _extract_jid(self, artifacts: dict, class_data: str) -> None:
        accessors = [method for method in self.METHOD_RE.finditer(class_data)
                     if 'public' in method.group('modifiers')
                     and method.group('sig') == self.RAW_STRING_SIG
                     and self.USER_FIELD_RE.search(method.group('body'))
                     and self.GET_SERVER_RE.search(method.group('body'))]
        if len(accessors) != 1:
            return
        artifacts['JID_CLASS_NAME'] = self.JID_CLASS.replace('/', '.')
        artifacts['JID_RAW_STRING_METHOD_NAME'] = accessors[0].group('name')
        self._found_jid = True

    def _extract_receipt_methods(self, artifacts: dict, class_data: str) -> None:
        methods = list(self.METHOD_RE.finditer(class_data))

        receipt_types = [method for method in methods
                         if 'public' in method.group('modifiers')
                         and self.RECEIPT_TYPE_SIG_RE.match(method.group('sig'))
                         and self.READ_RE.search(method.group('body'))
                         and self.READ_SELF_RE.search(method.group('body'))]
        if len(receipt_types) != 1:
            return
        receipt_type = receipt_types[0]

        # The helpers are whichever private predicates the receipt-type method
        # consults; naming them outright would hard-code an obfuscated name.
        helpers = [method.group('name') for method in methods
                   if 'private' in method.group('modifiers')
                   and self.PLAYED_GATE_SIG_RE.match(method.group('sig'))
                   and self._calls(receipt_type.group('body'), method.group('name'))]
        if len(helpers) < self.MIN_SHARED_HELPERS:
            return

        played_gates = [method for method in methods
                        if 'public' in method.group('modifiers')
                        and self.PLAYED_GATE_SIG_RE.match(method.group('sig'))
                        and sum(1 for helper in helpers
                                if self._calls(method.group('body'), helper))
                        >= self.MIN_SHARED_HELPERS]
        if len(played_gates) != 1:
            return
        played_gate = played_gates[0]

        class_match = CLASS_NAME_RE.match(class_data)
        if class_match is None:
            return

        artifacts['READ_RECEIPT_UTILS_CLASS_NAME'] = class_match.groupdict().get('name').replace('/', '.')
        artifacts['RECEIPT_TYPE_METHOD_NAME'] = receipt_type.group('name')
        artifacts['RECEIPT_TYPE_METHOD_SIG'] = receipt_type.group('sig')
        artifacts['PLAYED_RECEIPT_GATE_METHOD_NAME'] = played_gate.group('name')
        artifacts['PLAYED_RECEIPT_GATE_METHOD_SIG'] = played_gate.group('sig')
        self._found_utils = True
