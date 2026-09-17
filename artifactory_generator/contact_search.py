import re

from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder, CLASS_NAME_RE

METHOD_RE = re.compile(
    r'^\.method (?P<modifiers>[\w ]*?)(?P<name>[\w$]+)(?P<sig>\([^)\n]*\)[\w/$;\[]+)\n'
    r'(?P<body>.*?)^\.end method$',
    re.MULTILINE | re.DOTALL)

# The class every contact object is an instance of, named by the one signature
# on it that obfuscation cannot touch: com/whatsapp/infra/core/jid/ is not
# renamed, and this getter is the only method of that signature in the app on
# 2.26.29.74, 2.26.33.76, 2.26.36.71 and 2.26.36.72.
CONTACT_CLASS_RE = re.compile(
    r'^\.method [\w ]*?[\w$]+\(Ljava/lang/Class;\)Lcom/whatsapp/infra/core/jid/Jid;$',
    re.MULTILINE)


class ContactSearchFinder(SimpleArtifactoryFinder):
    """Finds the search every contact result on the search screen comes back through.

    WhatsApp indexes contacts into an FTS table keyed on ``wa_contacts._id``, so
    a person with several rows in that table has a document each and comes back
    once per row. On a device where that had happened the search returned 500
    rows for 320 people, and the screen listed one contact nine times.

    Anchored on the log line the method writes when it fails,
    ``FtsContactStore/searchContacts/error`` -- a string literal, so it survives
    every rename, and it is in exactly one method on the four builds checked.

    The shape is pinned as well as read. It has to be an instance method taking
    a query and a limit: the replacement is written for that argument layout,
    and a receiver that is there or not there is the difference between reading
    the query and reading whatever register sits in its place. The static
    method this one calls was the first target here, and ArtHooks came back
    into the replacement instead of into the original -- an infinite recursion
    that took the app down on the first search.
    """

    TAG = 'FtsContactStore/searchContacts/error'
    SIG_RE = re.compile(r'^\(L[^;()]+;I\)L[^;()]+;$')

    def __init__(self, args):
        super().__init__(args)
        self.is_once = True
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        return self.TAG in class_data

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        searches = [method for method in METHOD_RE.finditer(class_data)
                    if self.TAG in method.group('body')]
        if len(searches) != 1:
            return

        search = searches[0]
        if 'static' in search.group('modifiers').split():
            return
        if self.SIG_RE.match(search.group('sig')) is None:
            return

        class_match = CLASS_NAME_RE.match(class_data)
        if class_match is None:
            return

        artifacts['CONTACT_SEARCH_CLASS_NAME'] = class_match.groupdict().get('name').replace('/', '.')
        artifacts['CONTACT_SEARCH_METHOD_NAME'] = search.group('name')
        artifacts['CONTACT_SEARCH_METHOD_SIG'] = search.group('sig')
        self.is_found = True


class ContactAccessorsFinder(SimpleArtifactoryFinder):
    """Finds the two things a contact object has to answer for the de-dupe: its jid, and which row it came from.

    Both come off the same class, and they are one finder rather than two on
    purpose. stitch drops a finder from the list it is iterating the moment that
    finder fires (``generate_artifactory.py``), which skips whichever finder
    comes next for that same file -- so two finders anchored on one class means
    the second never runs.

    **The jid** is what two rows for one person agree on. Handed ``Jid`` itself
    the typed getter narrows to nothing and answers for every contact, which is
    what lets one call site key a whole list of results; jids are what
    WhatsApp's own jid-to-contact map is keyed on, so they compare by value.
    ``com/whatsapp/infra/core/jid/`` is not obfuscated, so the getter is
    described by a signature nothing can rename, and it is the only method of
    that signature in the app on the four builds checked.

    **The raw contact id** says which row the address book is behind: the rows
    WhatsApp wrote for itself carry a negative one -- seven of a contact's nine
    rows on the device this was found on, all ``-5``. The class has exactly two
    ``()J`` getters, and the other one is the table row id, which says so itself
    in the log line it writes while waiting for a provider. Taking the one that
    is *not* that identifies this one without writing either name down.
    """

    ROW_ID_LOG = 'WaContact/getId'
    LONG_GETTER_RE = re.compile(
        r'^\.method [\w ]*?(?P<name>[\w$]+)\(\)J\n(?P<body>.*?)^\.end method$',
        re.MULTILINE | re.DOTALL)
    JID_GETTER_RE = re.compile(
        r'^\.method [\w ]*?(?P<name>[\w$]+)\(Ljava/lang/Class;\)'
        r'Lcom/whatsapp/infra/core/jid/Jid;$',
        re.MULTILINE)

    def __init__(self, args):
        super().__init__(args)
        self.is_once = True
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        return CONTACT_CLASS_RE.search(class_data) is not None

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        jids = self.JID_GETTER_RE.findall(class_data)
        if len(jids) != 1:
            return

        getters = list(self.LONG_GETTER_RE.finditer(class_data))
        if len(getters) != 2:
            return
        row_ids = [getter for getter in getters if self.ROW_ID_LOG in getter.group('body')]
        if len(row_ids) != 1:
            return
        raw_ids = [getter for getter in getters if getter is not row_ids[0]]

        class_match = CLASS_NAME_RE.match(class_data)
        if class_match is None:
            return

        artifacts['CONTACT_JID_CLASS_NAME'] = class_match.groupdict().get('name').replace('/', '.')
        artifacts['CONTACT_JID_METHOD_NAME'] = jids[0]
        artifacts['CONTACT_RAW_ID_METHOD_NAME'] = raw_ids[0].group('name')
        self.is_found = True
