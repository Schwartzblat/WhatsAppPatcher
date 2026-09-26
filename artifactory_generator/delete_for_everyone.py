import re
from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder

from artifactory_generator.smali import CLASS_RE


class DeleteForEveryoneFinder(SimpleArtifactoryFinder):
    """Finds where the delete dialog decides a message is too old to revoke.

    Unlike the edit window, which is A/B property 3272 with 2983 behind it,
    the revoke window is a **literal** compiled into one method: the builder of
    the "Delete message?" dialog adds a fixed number of milliseconds to each
    selected message's timestamp and compares the sum against the
    server-corrected clock. 216000000 -- two days and twelve hours -- on every
    build examined, and the only occurrence of that number in the whole APK.
    No property reaches it, so there is nothing to override; the one lever is
    what the method reads.

    That is what the hook takes, so this finder resolves three things and they
    all come out of the same file:

    1. **The dialog builder.** Anchored on ``dialog/delete no messages``, the
       line it logs when the selection is empty -- a log string, so obfuscation
       leaves it alone. Six classes in a release hold that literal (the callers
       log it too), which is why the builder is then pinned by shape rather
       than by being the only match: it is the method that adds a constant to a
       long field of the message and compares the sum. One method, in one of
       those six classes, on all of 2.26.29.74, 2.26.33.76, 2.26.36.71,
       2.26.36.72 and 2.26.36.74 -- while the class itself was renamed in every
       one of them (``X.30q``, ``X.CkT``, ``X.AKy``, ``X.CpB``).

    2. **Which field holds the timestamp.** Read out of that same comparison,
       so the window's value is never written down here: what identifies the
       field is that a constant is added to it and the sum compared, not what
       the constant happens to be this release. ``A0F`` on every build
       examined, on a message class renamed in all of them (``X/1G7``,
       ``X/1If``, ``X/1Ex``).

    3. **Which class declares it**, so that :class:`DeleteForEveryone` can fail
       at load with a line in the log rather than silently find no field later.

    The builder is 460 to 520 instructions, far past ART's 32-unit inline
    budget, and an instance method -- both of which the hook needs.
    """

    ANCHOR = 'dialog/delete no messages'

    METHOD_RE = re.compile(
        r'^\.method (?P<modifiers>[\w ]*?)(?P<name>[\w$]+)(?P<sig>\([^)\n]*\)[\w/$;\[]+)\n'
        r'(?P<body>.*?)^\.end method$',
        re.MULTILINE | re.DOTALL)

    # apktool interleaves `.line` directives and blank lines between
    # instructions, and how many it emits varies from build to build.
    GAP = r'(?:[ \t]*(?:\.line \d+)?\n)*'

    # "this message is younger than a fixed window": a long field of the
    # message, a constant added to it, and the sum compared with the clock.
    WINDOW_RE = re.compile(
        r'iget-wide [vp]\d+, [vp]\d+, L(?P<owner>[\w/$]+);->(?P<field>[\w$]+):J\n'
        + GAP
        + r'[ \t]*const-wide(?:/16|/32)? [vp]\d+, 0x[0-9a-fA-F]+L?\n'
        + GAP
        + r'[ \t]*add-long(?:/2addr)?[ \t]')

    # The selection and the flag that says it may be revoked at all come last,
    # which is the shape the replacement has to mirror.
    DIALOG_SIG_RE = re.compile(
        r'^\(Landroid/content/Context;(?:L[\w/$]+;)+'
        r'Ljava/lang/String;Ljava/util/Set;Z\)L[\w/$]+;$')

    def __init__(self, args):
        super().__init__(args)
        self.is_once = True
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        return self.ANCHOR in class_data

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        builders = [(method, window) for method, window in
                    ((method, self.WINDOW_RE.search(method.group('body')))
                     for method in self.METHOD_RE.finditer(class_data))
                    if window is not None
                    and 'static' not in method.group('modifiers')
                    and self.DIALOG_SIG_RE.match(method.group('sig'))]
        if len(builders) != 1:
            return
        builder, window = builders[0]

        class_match = CLASS_RE.search(class_data)
        if class_match is None:
            return

        artifacts['REVOKE_DIALOG_CLASS_NAME'] = \
            class_match.groupdict().get('name').replace('/', '.')
        artifacts['REVOKE_DIALOG_METHOD_NAME'] = builder.group('name')
        artifacts['REVOKE_DIALOG_METHOD_SIG'] = builder.group('sig')
        artifacts['MESSAGE_TIMESTAMP_CLASS_NAME'] = window.group('owner').replace('/', '.')
        artifacts['MESSAGE_TIMESTAMP_FIELD_NAME'] = window.group('field')
        self.is_found = True
