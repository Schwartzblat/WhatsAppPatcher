import re

from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder

from artifactory_generator.smali import CLASS_RE

# What the props class throws when asked for an id it has no default for. It is
# a diagnostic string, so it is not renamed, and exactly one class in the app
# carries it: the abstract base every A/B property read goes through.
ANCHOR_RE = re.compile(r'const-string(?:/jumbo)? [vp]\d+, "Unknown IntField: "')

METHOD_RE = re.compile(
    r'^\.method (?P<modifiers>[\w ]*?)(?P<name>[\w$<>]+)'
    r'\((?P<params>[^)\n]*)\)(?P<ret>[\w/$;\[]+)\n'
    r'(?P<body>.*?)^\.end method$',
    re.MULTILINE | re.DOTALL)

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

# How many calls deep an accessor may hand off inside the class before it
# reaches the funnel. Two in every release examined -- accessor, typed static
# helper, funnel -- except 2.26.33.76's int path, which had an extra instance
# hop; one more than that is margin, not a guess at what comes next.
MAX_HOPS = 3

# ART inlines nothing over 32 dex code units, and every instruction is at least
# one. A funnel with more instructions than that cannot be copied into a call
# site by any compiler filter, which is the whole reason for hooking it rather
# than the accessors.
INLINE_BUDGET = 32

DIRECTIVE_RE = re.compile(r'^\s*(?:$|[.:#])')


def _instructions(body: str) -> int:
    count, in_annotation = 0, False
    for line in body.splitlines():
        stripped = line.strip()
        if stripped.startswith('.annotation'):
            in_annotation = True
        elif stripped.startswith('.end annotation'):
            in_annotation = False
        elif not in_annotation and not DIRECTIVE_RE.match(line):
            count += 1
    return count


class AbPropsFinder(SimpleArtifactoryFinder):
    """Finds the one method every A/B property read ends in.

    An A/B property is a number -- 33604 -- and the app asks for its value by
    that number alone, through one of five accessors on a single abstract
    class: give it an int, get back a boolean, an int, a float, a string or a
    JSON object. Between them they are called from 13,336 sites on 2.26.36.71.

    Those accessors used to be the hook. They are 8 dex code units each, so a
    warm profile's inline caches let dex2oat copy one into a hot call site
    behind a class check, and an override then silently stops applying there --
    seen at a read of property 16520 on 2.26.37.74 under the phone's own
    ``speed-profile``. Each accessor is only a hand-off, though: through a typed
    static helper into a private instance method that takes the property's id
    last and returns it boxed. That funnel is shared by all five, and is two
    hundred instructions long; nothing can inline it.

    So the accessors are still resolved, each by its shape, but only to be
    followed: every one of them has to arrive at the same funnel, or this does
    not fire. The class is pinned by the message it throws for an unknown id.

    Its concrete subclasses supply the default tables, and one of them
    (``X.0Ck`` on 2.26.37.74) overrides all five accessors: the properties
    object used during registration and Drive restore. Its reads reach the
    funnel too, on its own receiver, and are deliberately left alone -- which is
    what the boolean accessor's name is emitted for: the hook recognises that
    receiver by its declaring the accessor itself.

    Verified to converge on one funnel on 2.26.17.72, 2.26.27.85, 2.26.29.74,
    2.26.33.76, 2.26.36.71, 2.26.36.72, 2.26.36.74 and 2.26.37.74 -- four
    different names, and four reference parameters before the id in the oldest
    against five since.
    """

    def __init__(self, args):
        super().__init__(args)
        self.is_once = True
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        return ANCHOR_RE.search(class_data) is not None

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        class_match = CLASS_RE.search(class_data)
        if class_match is None:
            return
        descriptor = 'L%s;' % class_match.group('name')

        methods = {(m.group('name'), m.group('params'), m.group('ret')): m
                   for m in METHOD_RE.finditer(class_data)}

        found = {}
        for method in methods.values():
            modifiers = method.group('modifiers')
            if method.group('params') != 'I' or 'public' not in modifiers:
                continue
            # An abstract declaration has no body to follow, and a static one
            # is not what the app calls: every read site is invoke-virtual.
            if 'abstract' in modifiers or 'static' in modifiers:
                continue
            prefix = GETTERS.get(method.group('ret'))
            if prefix is not None:
                found.setdefault(prefix, []).append(method)

        # All five, each unambiguous. A missing or doubled shape means this is
        # not the class it looks like.
        if sorted(found) != sorted(GETTERS.values()):
            return
        if any(len(candidates) != 1 for candidates in found.values()):
            return

        funnels = {self._funnel(descriptor, methods, found[prefix][0]) for prefix in found}
        if len(funnels) != 1 or None in funnels:
            return
        funnel = methods.get(funnels.pop())
        if funnel is None or 'static' in funnel.group('modifiers'):
            return
        if _instructions(funnel.group('body')) <= INLINE_BUDGET:
            return

        artifacts['AB_PROPS_CLASS_NAME'] = class_match.group('name').replace('/', '.')
        artifacts['AB_PROPS_BOOL_METHOD_NAME'] = found['BOOL'][0].group('name')
        artifacts['AB_PROPS_FUNNEL_METHOD_NAME'] = funnel.group('name')
        artifacts['AB_PROPS_FUNNEL_METHOD_SIG'] = '(%s)%s' % (funnel.group('params'), funnel.group('ret'))
        self.is_found = True

    @staticmethod
    def _funnel(descriptor, methods, accessor):
        """The funnel this accessor hands off to, or None when it does not
        reach exactly one.

        The funnel is recognised by the call rather than by a list of
        parameter types, which moved between releases: an invoke-direct into
        this class, of a method returning Object whose last parameter is the
        int id. Anything else the class calls on the way -- the typed static
        helpers, an instance hop -- is followed, up to MAX_HOPS deep.
        """
        call_re = re.compile(
            r'invoke-(?P<kind>static|direct)(?:/range)? \{[^}]*\}, %s->'
            r'(?P<name>[\w$<>]+)\((?P<params>[^)\n]*)\)(?P<ret>[\w/$;\[]+)' % re.escape(descriptor))
        reached = set()
        frontier = [accessor]
        for _ in range(MAX_HOPS):
            following = []
            for method in frontier:
                for call in call_re.finditer(method.group('body')):
                    key = (call.group('name'), call.group('params'), call.group('ret'))
                    if (call.group('kind') == 'direct' and key[2] == 'Ljava/lang/Object;'
                            and key[1].endswith('I') and not key[1].endswith('[I')):
                        reached.add(key)
                    elif key in methods and key[0] != '<init>':
                        following.append(methods[key])
            if reached:
                break
            frontier = following
        return reached.pop() if len(reached) == 1 else None
