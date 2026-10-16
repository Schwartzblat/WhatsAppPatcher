import re

from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder

from artifactory_generator.smali import CLASS_RE, METHOD_RE

# The startup task's own name for itself, returned by its tag accessor. A log
# and trace label, not a symbol, so R8 leaves it alone: it is the same on every
# release examined while the class went X.DGW -> X.G21 -> X.3ba -> X.Fuz.
TAG = 'WebsiteDistributionCheck'
TAG_RE = re.compile(r'const-string(?:/jumbo)? [vp]\d+, "%s"' % TAG)

# What the check itself logs and reads: the download source it sets up, and the
# "unknown sources" setting it refuses to go on without. Both are needed, so a
# method that only mentions the URL prefix is not taken for the check.
SOURCE_RE = re.compile(r'const-string(?:/jumbo)? [vp]\d+, "upgrade source initialized; path="')
SETTING_RE = re.compile(r'const-string(?:/jumbo)? [vp]\d+, "install_non_market_apps"')

CHECK_SIG = '()V'
TAG_SIG = '()Ljava/lang/String;'

# ART inlines nothing over 32 dex code units, and every instruction is at least
# one, so a body with more instructions than that is never copied into a caller
# and the hook on its entry point is always reached.
INLINE_BUDGET = 32
DIRECTIVE_RE = re.compile(r'^\s*(?:$|[.:#])')


def _instructions(body: str) -> int:
    return sum(1 for line in body.splitlines() if not DIRECTIVE_RE.match(line))


class UpgradeCheckFinder(SimpleArtifactoryFinder):
    """Finds WhatsApp's own self-updater: the startup task that schedules it.

    A website build of WhatsApp updates itself. On every launch one startup task
    -- the one whose tag is {TAG} -- sets up the download URLs and, when the
    phone allows installs from unknown sources and six hours have passed since
    the last check, posts the job that fetches the version file, downloads the
    whole APK, verifies it and leaves a sentinel. The next launch then offers to
    install it, over a build signed with a different key, which fails. So the
    useful hook is this task: with it a no-op nothing is downloaded and the
    install prompt has nothing to offer.

    Not the helper class that holds the APK URL. Its methods are each under the
    inline budget (the URL getter is 21 code units, the sentinel writer 25), none
    of them starts anything, and on 2.26.39.79 the URL and the sentinel log
    string are not even in the same class. The task is one method of 208 code
    units on 2.26.40.72.

    The task is still registered on builds that do not update themselves
    (2.26.39.79's is a bare return-void), and the finder declines there: no
    method carries the anchors, so the hook is simply not installed.

    Expected: X.Fuz.Bf0 on 2.26.40.72, X.G21.Bbe on 2.26.37.74, X.DGW.BSK on
    2.26.29.74 (whose tag accessor is Az7, not getTag).
    """

    def __init__(self, args):
        super().__init__(args)
        self.is_once = True
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        return TAG_RE.search(class_data) is not None and SOURCE_RE.search(class_data) is not None

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        methods = list(METHOD_RE.finditer(class_data))
        # The tag has to come from the class's own tag accessor: that is what
        # makes it the startup task rather than something that names it.
        if not any(m.group('sig') == TAG_SIG and TAG_RE.search(m.group('body')) for m in methods):
            return
        checks = [m for m in methods
                  if m.group('sig') == CHECK_SIG
                  and SOURCE_RE.search(m.group('body'))
                  and SETTING_RE.search(m.group('body'))]
        if len(checks) != 1:
            return
        check = checks[0]
        # R8 staticizes a method whose receiver goes unused without touching the
        # descriptor, and a static target re-enters its own replacement.
        if 'static' in check.group('modifiers').split():
            return
        if _instructions(check.group('body')) <= INLINE_BUDGET:
            return
        class_match = CLASS_RE.search(class_data)
        if class_match is None:
            return
        artifacts['UPGRADE_CHECK_CLASS_NAME'] = class_match.group('name').replace('/', '.')
        artifacts['UPGRADE_CHECK_METHOD_NAME'] = check.group('name')
        artifacts['UPGRADE_CHECK_METHOD_SIG'] = CHECK_SIG
        self.is_found = True
