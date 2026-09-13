import re
from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder, CLASS_NAME_RE


class SettingsScreenFinder(SimpleArtifactoryFinder):
    """Finds the activity behind WhatsApp's main Settings screen.

    The entry row is injected from a hook on ``android.app.Activity.onResume``,
    a framework method obfuscation cannot touch, so the one app-specific value
    needed is which resumed activity is the settings screen.

    That class name happens to survive obfuscation -- everything under
    ``com/whatsapp/settings/ui/`` keeps its source name -- but the package has
    moved before (``com.whatsapp.settings.Settings`` in older builds), so it is
    resolved rather than written down. Three properties have to hold at once,
    and none of them is the class's path:

    1. it declares ``onCreate(Landroid/os/Bundle;)V``, so it is an Activity and
       not one of the seven lambda/helper classes that log under the settings
       screen's tag;
    2. it logs under its *own* simple name -- some const-string in the class
       starts with ``<SimpleName>/``, as in
       ``SettingsTabActivity/onCreate/no-me``. Helper classes carry the same
       prefix but their own name is something else, so this rule keeps them
       out while surviving a rename of the class: the tag is derived from the
       name rather than compared to a fixed one. The name must still contain
       ``Settings``, which is what makes the tag meaningful;
    3. it carries at least two of the settings home's own feature markers
       (below). Rule 1 and 2 together still admit five activities per build --
       SettingsAccount, SettingsContactsActivity, SettingsPasskeys,
       AppAuthSettingsActivity and the one wanted -- and these markers are what
       separate the screen that *hosts* the settings list from the screens it
       links to. Two of five are required rather than one so that a single
       retired marker does not take the finder dark, and a stray marker in a
       future build does not drag in a sibling.

    Verified to resolve ``com/whatsapp/settings/ui/SettingsTabActivity``, and
    nothing else, on 2.26.17.72, 2.26.27.85, 2.26.29.74 and 2.26.33.76.
    """

    # Screen-name and view tags belonging to the settings home: the analytics
    # screen name, the contact-photo loader tag, the language selector, the
    # appearance cell and the "what's this" entry point. All five are present
    # in every build examined, and in no other activity.
    MARKERS = (
        '"settings_activity"',
        '"settings-activity-contact-photo"',
        '"language_selector"',
        '"appearance_cell"',
        '"target_settings_wfal"',
    )
    MIN_MARKERS = 2

    ON_CREATE_RE = re.compile(
        r'^\.method (?:public|protected) (?:\w+ )*onCreate\(Landroid/os/Bundle;\)V', re.MULTILINE)

    LOG_TAG_RE = re.compile(r'const-string(?:/jumbo)? [vp]\d+, "(?P<tag>[\w$]+)/')

    def __init__(self, args):
        super().__init__(args)
        self.is_once = True
        self.is_found = False

    def _marker_count(self, class_data: str) -> int:
        return sum(1 for marker in self.MARKERS if marker in class_data)

    def class_filter(self, class_data: str) -> bool:
        return (self._marker_count(class_data) >= self.MIN_MARKERS
                and self.ON_CREATE_RE.search(class_data) is not None)

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        # Re-asserted rather than assumed from class_filter, so every rule that
        # decides the target lives in one place.
        if self._marker_count(class_data) < self.MIN_MARKERS:
            return
        if self.ON_CREATE_RE.search(class_data) is None:
            return
        class_match = CLASS_NAME_RE.match(class_data)
        if class_match is None:
            return
        name = class_match.groupdict().get('name')
        simple_name = name.rsplit('/', 1)[-1]
        if 'Settings' not in simple_name:
            return
        if simple_name not in self.LOG_TAG_RE.findall(class_data):
            return
        artifacts['SETTINGS_ACTIVITY_CLASS_NAME'] = name.replace('/', '.')
        self.is_found = True
