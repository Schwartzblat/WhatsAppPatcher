import re

from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder, CLASS_NAME_RE

# What the props class throws when asked for an id it has no default for. It is
# a diagnostic string, so it is not renamed, and exactly one class in the app
# carries it: the abstract base every A/B property read goes through.
ANCHOR_RE = re.compile(r'const-string(?:/jumbo)? [vp]\d+, "Unknown IntField: "')

METHOD_RE = re.compile(
    r'^\.method (?P<modifiers>[\w ]*?)(?P<name>[\w$]+)'
    r'\((?P<params>[^)\n]*)\)(?P<ret>[\w/$;\[]+)$',
    re.MULTILINE)

# One accessor per value type, keyed by the return descriptor. The names are
# outputs -- across 2.26.17.72, 2.26.27.85, 2.26.29.74, 2.26.33.76 and
# 2.26.36.71 the boolean one alone was A0n, A0q, A0s, A0t and A0y -- but the
# shapes did not move, and each is the only public instance method taking an
# int and returning that type.
GETTERS = {
    'Z': 'BOOL',
    'I': 'INT',
    'F': 'FLOAT',
    'Ljava/lang/String;': 'STRING',
    'Lorg/json/JSONObject;': 'JSON',
}


class AbPropsFinder(SimpleArtifactoryFinder):
    """Finds the five accessors the app reads its A/B properties through.

    An A/B property is a number -- 33604 -- and the app asks for its value by
    that number alone. Every such read lands in one of five methods on a single
    abstract class: give it an int, get back a boolean, an int, a float, a
    string or a JSON object. Between them they are called from 13,336 sites on
    2.26.36.71, against 892 for the variants that take a typed key object
    instead of a number, so these five are what a hook has to cover.

    The class is pinned by the message it throws for an unknown id, and each
    accessor by its shape within it. Nothing else in the class takes an int and
    returns one of those five types, which is why no name is written down here.

    Its concrete subclasses supply the default tables, and one of them
    (``X.0Cf`` on 2.26.36.71) overrides all five accessors and so escapes a hook
    placed here. That one is the properties object used during registration and
    Drive restore -- 51 references against 635 for the one the rest of the app
    holds, which does not override -- and it is deliberately left alone.

    Verified to resolve the class, and all five accessors, on 2.26.17.72,
    2.26.27.85, 2.26.29.74, 2.26.33.76 and 2.26.36.71.
    """

    def __init__(self, args):
        super().__init__(args)
        self.is_once = True
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        return ANCHOR_RE.search(class_data) is not None

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        class_match = CLASS_NAME_RE.match(class_data)
        if class_match is None:
            return

        found = {}
        for method in METHOD_RE.finditer(class_data):
            modifiers = method.group('modifiers')
            if method.group('params') != 'I' or 'public' not in modifiers:
                continue
            # An abstract declaration has no entry point to redirect, and a
            # static one cannot be hooked at all: its backup would re-enter the
            # replacement and take the app down on the first read.
            if 'abstract' in modifiers or 'static' in modifiers:
                continue
            prefix = GETTERS.get(method.group('ret'))
            if prefix is not None:
                found.setdefault(prefix, []).append(method.group('name'))

        # All five, each unambiguous. A missing or doubled shape means this is
        # not the class it looks like, and a half-hooked accessor set would read
        # properties the overrides never reach.
        if sorted(found) != sorted(GETTERS.values()):
            return
        if any(len(names) != 1 for names in found.values()):
            return

        artifacts['AB_PROPS_CLASS_NAME'] = class_match.groupdict().get('name').replace('/', '.')
        for descriptor, prefix in GETTERS.items():
            artifacts['AB_PROPS_%s_METHOD_NAME' % prefix] = found[prefix][0]
            artifacts['AB_PROPS_%s_METHOD_SIG' % prefix] = '(I)%s' % descriptor
        self.is_found = True
