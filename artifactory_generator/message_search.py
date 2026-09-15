import re

from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder, CLASS_NAME_RE


class MessageSearchFinder(SimpleArtifactoryFinder):
    """Finds the one method every message search funnels through, and how it names a jid.

    WhatsApp assembles the FTS MATCH expression in two different places -- the
    global/chat-scoped builder and the FTS-v2 variant -- but both, and the
    starred search and the per-chat result counts, end in the same method: the
    one that appends the ``fts_namespace:`` terms to an expression it is handed
    and returns the finished string. Hooking it is what lets a patch add terms
    that land inside the content group, with the namespace scoping still ANDed
    around the outside.

    Nothing about that method's name survives a release, so it is taken by the
    one literal it must contain and by its shape: two reference parameters, a
    ``String`` third parameter, a ``String`` return. Other methods in the class
    share that signature, which is why the literal is required in the body
    rather than anywhere in the class.

    The second half is the encoding. ``fts_jid`` does not hold jids; it holds
    two space-separated tokens, each ``Long.toString(jidRowId + 10, 36)`` over
    the row id of WhatsApp's own jid table. Both integers are read out of the
    encoder rather than written down here: they are the app's constants, not
    this patch's, and a build that changes either would otherwise produce
    tokens that match nothing while everything still compiles and runs.

    The offset is optional. A build that encodes with no offset at all has no
    ``add-long``, and the finder reports 0 rather than declining to fire.
    """

    NAMESPACE_RE = re.compile(r'const-string(?:/jumbo)? [vp]\d+, "fts_namespace:"')

    METHOD_RE = re.compile(
        r'^\.method (?P<modifiers>[\w ]*?)(?P<name>[\w$]+)'
        r'(?P<sig>\(L[^;()]+;L[^;()]+;Ljava/lang/String;\)Ljava/lang/String;)\n'
        r'(?P<body>.*?)^\.end method$',
        re.MULTILINE | re.DOTALL)

    # Matched against a body with baksmali's .line markers and blank lines
    # stripped, so the constants and the call they feed have to be adjacent --
    # which is what makes this "encodes a row id" rather than "mentions 36".
    NOISE_RE = re.compile(r'^[ \t]*(?:\.line \d+)?[ \t]*\n', re.MULTILINE)

    TOKEN_RE = re.compile(
        r'(?:[ \t]*const-wide(?:/16|/32|/high16)? (?P<offreg>[vp]\d+), (?P<offset>0x[0-9a-f]+)\n'
        r'[ \t]*add-long/2addr [vp]\d+, (?P=offreg)\n)?'
        r'[ \t]*const(?:/4|/16|/high16)? (?P<radreg>[vp]\d+), (?P<radix>0x[0-9a-f]+)\n'
        r'[ \t]*invoke-static \{[vp]\d+, [vp]\d+, (?P=radreg)\}, '
        r'Ljava/lang/Long;->toString\(JI\)Ljava/lang/String;')

    def __init__(self, args):
        super().__init__(args)
        self.is_once = True
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        return self.NAMESPACE_RE.search(class_data) is not None

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        funnels = [match for match in self.METHOD_RE.finditer(class_data)
                   if self.NAMESPACE_RE.search(match.group('body'))]
        if len(funnels) != 1:
            return

        encoders = list(self.TOKEN_RE.finditer(self.NOISE_RE.sub('', class_data)))
        if len(encoders) != 1:
            return

        class_match = CLASS_NAME_RE.match(class_data)
        if class_match is None:
            return

        offset = encoders[0].group('offset')
        artifacts['MESSAGE_SEARCH_CLASS_NAME'] = class_match.groupdict().get('name').replace('/', '.')
        artifacts['MESSAGE_SEARCH_METHOD_NAME'] = funnels[0].group('name')
        artifacts['MESSAGE_SEARCH_METHOD_SIG'] = funnels[0].group('sig')
        artifacts['MESSAGE_SEARCH_TOKEN_OFFSET'] = str(int(offset, 16)) if offset else '0'
        artifacts['MESSAGE_SEARCH_TOKEN_RADIX'] = str(int(encoders[0].group('radix'), 16))
        self.is_found = True
