import re
from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder

from artifactory_generator.smali import CLASS_RE


class DecryptProtobufFinder(SimpleArtifactoryFinder):
    """The inbound message protobuf, and the builder that still holds it writable.

    The message class is pinned by a field name protobuf keeps. The hook does
    *not* go on its ``parseFrom``: that is a one-line static forwarder, so
    dex2oat inlines it into every call site and an entry-point hook on it stops
    being reached the moment background dexopt compiles the app -- installed,
    logged as hooked, and never entered again. The target is the builder of
    WhatsApp's ``ParseE2EMessageParams`` instead, whose build method is ~78
    instructions, far past anything ART will inline, and is the last point on
    the receive path where the protobuf can still be rewritten.
    """

    MESSAGE_FIELD = 'newsletterFollowerInviteMessage_'

    #: ``<init>(key, message, message, timestamp)``. The repeated parameter is
    #: the shape that pins the builder; a handful of unrelated classes share it,
    #: so the type it repeats is checked against the message class rather than
    #: trusted.
    BUILDER_CTOR_RE = re.compile(
        r'^\.method public constructor <init>\(L[\w/$]+;(?P<message>L[\w/$]+;)(?P=message)J\)V',
        re.M)
    #: The builder's no-arg build method. Framework return types are excluded so
    #: that the one decoy sharing the constructor shape -- a JSON envelope -- is
    #: not weighed as a candidate.
    BUILD_RE = re.compile(
        r'^\.method public final (?P<method_name>\w+)'
        r'(?P<sig>\(\)L(?!java/|javax/|android|org/|kotlin/)[\w/$]+;)$', re.M)

    def __init__(self, args):
        super().__init__(args)
        self.is_once = True
        self.is_found = False
        self.message_class = None
        #: class name -> (message descriptor, build method, build signature).
        #: Kept because files arrive in filesystem order: the builder is usually
        #: read before the message class that identifies which builder it is.
        self.builders = {}

    def class_filter(self, class_data: str) -> bool:
        return self.MESSAGE_FIELD in class_data or self.BUILDER_CTOR_RE.search(class_data) is not None

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        name_match = CLASS_RE.search(class_data)
        if name_match is None:
            return
        name = name_match.groupdict().get('name')

        if self.MESSAGE_FIELD in class_data:
            self.message_class = name

        ctor = self.BUILDER_CTOR_RE.search(class_data)
        if ctor is not None:
            builds = list(self.BUILD_RE.finditer(class_data))
            if len(builds) == 1:
                self.builders[name] = (ctor.groupdict().get('message'),
                                       builds[0].groupdict().get('method_name'),
                                       builds[0].groupdict().get('sig'))

        if self.message_class is None:
            return
        descriptor = 'L%s;' % self.message_class
        matches = [(name, build) for name, build in self.builders.items() if build[0] == descriptor]
        if len(matches) != 1:
            return

        builder_name, (_, method_name, sig) = matches[0]
        artifacts['DECRYPT_PROTOBUF_CLASS_NAME'] = self.message_class.replace('/', '.')
        artifacts['PARSE_PARAMS_BUILDER_CLASS_NAME'] = builder_name.replace('/', '.')
        artifacts['PARSE_PARAMS_BUILDER_METHOD_NAME'] = method_name
        artifacts['PARSE_PARAMS_BUILDER_METHOD_SIG'] = sig
        self.is_found = True
