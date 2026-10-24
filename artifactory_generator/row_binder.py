import re
from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder

from artifactory_generator.smali import CLASS_RE


class RowBinderFinder(SimpleArtifactoryFinder):
    """Finds the conversation list adapter and its position -> message accessor.

    Anchored on a framework superclass and on the adapter's own log prefix,
    neither of which obfuscation can rename. The class must satisfy all four of:

    1. ``.super Landroid/widget/CursorAdapter;`` or, from 2.26.42,
       ``.super Landroid/widget/BaseAdapter;`` -- the framework base class is a
       name WhatsApp's obfuscator cannot touch;
    2. it carries a ``"ConversationCursorAdapter...`` log string, which
       WhatsApp kept when the adapter stopped being cursor-backed. Among the
       app's many BaseAdapters this is what picks the conversation's; it is
       required of a CursorAdapter too, so one rule decides on every build;
    3. it declares the framework ``getView(ILandroid/view/View;
       Landroid/view/ViewGroup;)Landroid/view/View;`` *itself*. An adapter that
       binds through ``newView``/``bindView`` only (the audio picker's, for
       one) does not qualify, and requiring the declaration also keeps
       ``ArtHooks.find_function`` -- which walks superclasses -- from resolving a
       framework ``getView`` and redirecting every adapter in the app;
    4. it declares exactly one public, non-bridge, non-static ``(I)L<ref>;``
       method: the item accessor that turns the bound position into the row's
       FMessage.

    Rule 4's modifier list deliberately excludes ``bridge``/``synthetic``.
    ``Adapter.getItem`` is generified, so the adapter always carries a
    ``public bridge synthetic getItem(I)Ljava/lang/Object;`` of exactly the
    accessor's shape; counting it would make the accessor look ambiguous and
    take the finder dark. ``static`` is excluded for the same reason and
    because the accessor is invoked on the adapter instance.

    History, which is why the anchor is the superclass and not the method
    shape. Two earlier rules both selected an adapter belonging to a *different
    screen*:

    - Shape alone (``(View, ViewGroup, <message>, int) -> View``, the ``B6J``
      family) matched two classes per build: a real adapter and a thin
      delegating wrapper whose binder is a stub reached only through an
      interface field. Which one won was decided by filesystem scan order.
    - Adding reachability (the binder must be invoked on ``p0`` from inside the
      declaring class) made the choice deterministic but not correct: on
      2.26.33.76 the surviving candidate was ``X/5mI``, referenced only by
      KeptMessagesActivity and StarredMessagesActivity. The hook installed and
      logged success on a screen nobody opens, while the conversation kept
      rendering undecorated.

    The conversation adapter has no ``B6J``-shaped method at all -- a
    CursorAdapter binds through ``getView``, so the shape the old rules hunted
    for never existed on the screen this feature targets. Reachability to the
    right screen is what the old rules could not express; extending
    ``CursorAdapter`` and declaring ``getView`` expressed it structurally,
    leaving exactly one candidate on every build examined (2.26.17.72,
    2.26.27.85, 2.26.29.74, 2.26.33.76), each referenced by
    ``com/whatsapp/Conversation`` and each without subclasses.

    2.26.42 made the same adapter (``X/DS2``, the same ``getView`` and accessor)
    a plain ``BaseAdapter``, and the CursorAdapter rule found nothing. The log
    prefix is what replaces "cursor-backed" as the screen's signature: with it,
    every build from 2.26.17.72 to 2.26.42.71 again has exactly one candidate.

    Method modifiers are otherwise matched tolerantly (final/bridge/synthetic/
    synchronized on ``getView``) so a recompiled build does not take the finder
    dark, while the signature shapes themselves stay exact.
    """

    MODIFIERS = r'(?:(?:final|bridge|synthetic|synchronized) )*'
    # No bridge/synthetic/static here: see rule 4 in the class docstring.
    ITEM_MODIFIERS = r'(?:(?:final|synchronized) )*'

    ADAPTER_SUPER_RE = re.compile(r'^\.super Landroid/widget/(?:CursorAdapter|BaseAdapter);\s*$', re.MULTILINE)

    LOG_PREFIX_RE = re.compile(r'const-string(?:/jumbo)? [vp]\d+, "ConversationCursorAdapter[/.]')

    GET_VIEW_RE = re.compile(
        r'\.method public ' + MODIFIERS + r'(?P<method_name>getView)'
        r'(?P<sig>\(ILandroid/view/View;Landroid/view/ViewGroup;\)Landroid/view/View;)')

    ITEM_RE = re.compile(
        r'\.method public ' + ITEM_MODIFIERS + r'(?P<method_name>\w+)'
        r'(?P<sig>\(I\)L[\w/$]+;)')

    def __init__(self, args):
        super().__init__(args)
        self.is_once = True
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        return (self.ADAPTER_SUPER_RE.search(class_data) is not None
                and self.LOG_PREFIX_RE.search(class_data) is not None
                and self.GET_VIEW_RE.search(class_data) is not None)

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        # Re-asserted rather than assumed from class_filter: every rule that
        # decides this finder's target belongs in one place, so a caller that
        # skips the filter cannot fire it on the wrong adapter.
        if self.ADAPTER_SUPER_RE.search(class_data) is None or self.LOG_PREFIX_RE.search(class_data) is None:
            return
        get_view = self.GET_VIEW_RE.search(class_data)
        if get_view is None:
            return
        items = list(self.ITEM_RE.finditer(class_data))
        if len(items) != 1:
            # Ambiguity means do not fire; the gate then reports the finder as
            # never fired rather than the hook decorating nothing at runtime.
            return
        class_match = CLASS_RE.search(class_data)
        if class_match is None:
            return
        artifacts['ROW_BINDER_CLASS_NAME'] = class_match.groupdict().get('name').replace('/', '.')
        artifacts['ROW_BINDER_METHOD_NAME'] = get_view.groupdict().get('method_name')
        artifacts['ROW_BINDER_METHOD_SIG'] = get_view.groupdict().get('sig')
        artifacts['ROW_ITEM_METHOD_NAME'] = items[0].groupdict().get('method_name')
        artifacts['ROW_ITEM_METHOD_SIG'] = items[0].groupdict().get('sig')
        self.is_found = True
