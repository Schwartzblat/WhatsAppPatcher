import re
from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder

from artifactory_generator.smali import CLASS_RE


class GroupInfoFinder(SimpleArtifactoryFinder):
    """Finds the activity behind WhatsApp's group info screen.

    The statistics row is injected from a hook on ``android.app.Activity``'s
    ``onResume``, a framework method obfuscation cannot touch, so the one
    app-specific value needed is which resumed activity is the group info
    screen.

    The class name survives obfuscation -- ``com/whatsapp/chatinfo/group/``
    keeps its source names -- but the package has already moved once (it was
    ``com/whatsapp/groupinfo/``) and the dex file it lands in moves between
    releases, so neither is an input here. Three properties decide it:

    1. it carries the log string ``group_info/on_create: exiting due to null
       gid``, which appears in exactly one file per release (checked on
       2.26.29.74, 2.26.33.76 and 2.26.36.71);
    2. it declares ``onCreate(Landroid/os/Bundle;)V``, so it is the Activity
       and not one of the helpers that log under the same tag;
    3. it logs under its *own* simple name -- some const-string starts with
       ``<SimpleName>/``, as in ``GroupChatInfoActivity/refresh``. Derived from
       the class name rather than compared to a fixed one, so a rename
       survives; the name must still contain ``GroupChatInfo``, which is what
       makes the tag meaningful.
    """

    MARKER = '"group_info/on_create: exiting due to null gid"'

    NAME_FRAGMENT = 'GroupChatInfo'

    ON_CREATE_RE = re.compile(
        r'^\.method (?:public|protected) (?:\w+ )*onCreate\(Landroid/os/Bundle;\)V', re.MULTILINE)

    LOG_TAG_RE = re.compile(r'const-string(?:/jumbo)? [vp]\d+, "(?P<tag>[\w$]+)/')

    def __init__(self, args):
        super().__init__(args)
        self.is_once = True
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        return self.MARKER in class_data and self.ON_CREATE_RE.search(class_data) is not None

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        # Re-asserted rather than assumed from class_filter, so every rule that
        # decides the target lives in one place.
        if self.MARKER not in class_data:
            return
        if self.ON_CREATE_RE.search(class_data) is None:
            return
        class_match = CLASS_RE.search(class_data)
        if class_match is None:
            return
        name = class_match.groupdict().get('name')
        simple_name = name.rsplit('/', 1)[-1]
        if self.NAME_FRAGMENT not in simple_name:
            return
        if simple_name not in self.LOG_TAG_RE.findall(class_data):
            return
        artifacts['GROUP_INFO_ACTIVITY_CLASS_NAME'] = name.replace('/', '.')
        self.is_found = True
