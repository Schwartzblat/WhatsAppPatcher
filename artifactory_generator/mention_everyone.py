import re
from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder, CLASS_NAME_RE


class MentionEveryoneFinder(SimpleArtifactoryFinder):
    """Finds the composer's mention list, and the marker that means "everyone".

    A mention is not derived from the message text. It is a list that travels
    beside it: the composer hands one over, the message carries it, and the
    outgoing ``ContextInfo`` is filled from it. Nothing checks that an entry in
    the list has a matching ``@name`` in the body, which is what makes tagging
    a group on a word of the user's choosing possible at all.

    Two things have to be resolved, and both come out of the same file:

    1. **Where the list is produced.** ``MentionableEntry`` is the composer's
       text widget, and it is one of the few classes in the app whose name
       obfuscation leaves alone -- only its package moved
       (``com/whatsapp/mentions/`` to ``com/whatsapp/mentions/ui/``), so the
       anchor allows for that and nothing else. Its accessor is pinned as the
       only public ``()Ljava/util/List;`` it declares, which held on every
       build examined, rather than by the name ``getMentions``: the name is
       readable today and is still an output, not an input. A ``static`` match
       is refused outright -- ArtHooks cannot hook a static method, its backup
       re-enters the replacement, and the host app dies on the first call.

    2. **What "everyone" is.** WhatsApp's own @all is a singleton object, the
       one entry in that list that is not a per-user mention. It is read inside
       the accessor itself, and it is the only **self-typed** static field read
       there -- the span-kind constants beside it are ``Integer``s. That shape
       is what identifies it, because the class is renamed every release like
       everything else, and between two builds of the same week: ``X.A3q``,
       ``X.AUl``, ``X.DIw``, ``X.AZv`` on the four builds below. It is
       cross-checked by the class holding
       ``MentionMessageStore/insertMention``, which stores that same type as
       ``mention_type`` 1 and is the unique holder of that string in every
       build; the check is not in this finder, which would need a second
       anchor class to do it, and a second finder on the same class would never
       fire.

    Verified to resolve both, and nothing else, on 2.26.29.74, 2.26.33.76,
    2.26.36.71 and 2.26.36.72.
    """

    ENTRY_CLASS_RE = re.compile(r'^com/whatsapp/mentions/(?:[\w/]+/)?MentionableEntry$')
    MENTIONS_SIG = '()Ljava/util/List;'

    METHOD_RE = re.compile(
        r'^\.method (?P<modifiers>[\w ]*?)(?P<name>[\w$]+)(?P<sig>\([^)\n]*\)[\w/$;\[]+)\n'
        r'(?P<body>.*?)^\.end method$',
        re.MULTILINE | re.DOTALL)
    # A static field whose type is the class declaring it: a singleton, which is
    # how the app itself reaches the everyone marker.
    SINGLETON_RE = re.compile(r'sget-object [vp]\d+, (?P<owner>L[\w/$]+;)->(?P<field>[\w$]+):(?P=owner)')

    def __init__(self, args):
        super().__init__(args)
        self.is_once = True
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        match = CLASS_NAME_RE.match(class_data)
        return (match is not None
                and self.ENTRY_CLASS_RE.match(match.groupdict().get('name')) is not None
                and self.MENTIONS_SIG in class_data)

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        accessors = [method for method in self.METHOD_RE.finditer(class_data)
                     if method.group('sig') == self.MENTIONS_SIG
                     and 'public' in method.group('modifiers')
                     and 'static' not in method.group('modifiers')]
        if len(accessors) != 1:
            return
        accessor = accessors[0]

        markers = set(self.SINGLETON_RE.findall(accessor.group('body')))
        if len(markers) != 1:
            return
        owner, field = markers.pop()

        artifacts['MENTION_ENTRY_CLASS_NAME'] = \
            CLASS_NAME_RE.match(class_data).groupdict().get('name').replace('/', '.')
        artifacts['MENTION_GET_METHOD_NAME'] = accessor.group('name')
        artifacts['MENTION_GET_METHOD_SIG'] = self.MENTIONS_SIG
        artifacts['MENTION_EVERYONE_CLASS_NAME'] = owner[1:-1].replace('/', '.')
        artifacts['MENTION_EVERYONE_FIELD_NAME'] = field
        self.is_found = True
