import re
from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder

from artifactory_generator.smali import CLASS_RE, GAP, METHOD_RE, lambda_case


def _method_at(class_data, position):
    """The method whose body contains ``position``, or None."""
    for method in METHOD_RE.finditer(class_data):
        if method.start('body') <= position < method.end('body'):
            return method
    return None


def _dotted(descriptor):
    return descriptor.replace('/', '.')


def _instructions(code):
    """The instruction lines of a stretch of smali, labels and directives left out."""
    return [line for line in (raw.strip() for raw in code.split('\n'))
            if line and not line.startswith(('.', ':', '#'))]


class PinnedChatsFinder(SimpleArtifactoryFinder):
    """Finds the three places WhatsApp enforces its pinned-chat cap.

    The cap is ``isBenefitActive(PINNED_CHATS) ? 20 : 3``, and both numbers are
    literals compiled into each place that enforces it. No property reaches any
    of them and there is no single method to change, so :class:`PinnedChats`
    takes each one separately, and this finder resolves all three. They live
    in three unrelated classes, so it fires only once it has seen each of them;
    a release where one stops matching leaves that part's keys unsubstituted,
    which is how the gate says which one.

    1. **The benefit check** every paid ("WhatsApp Plus") benefit is decided by.
       The cap is 20 rather than 3 when it says ``PINNED_CHATS`` is active, and
       it is what the sync handler -- the one enforcement point that cannot be
       taken on its own -- reads. It is also what keeps the subscription upsell
       from replacing a pin once three are taken. The class logs
       ``PromoEligibilityManager/refreshEligibility: promo eligibility
       disabled``, and inside it the check is the one public instance
       ``(Benefit)Z`` method that caches its answer in a ``ConcurrentHashMap``
       and defers to a sibling of the same shape when the cache has nothing.
       Since 2.26.37.74 it also reads ``retainedUntilAppRestart`` off the
       benefit before the cache, which is what broke the regex
       :class:`WhatsAppPlusFinder` matches this method with; this one does not
       depend on the order. About 47 code units, past ART's 32-unit inline
       budget.

    2. **The trim** that unpins everything past the cap. WhatsApp posts it
       every time the Chats tab is shown (``ConversationsFragment``'s
       tab-selected callback), so a pin past the cap survives only until the
       user next looks at their chats. It is not a method of its own: R8 merged
       it into one of the release's ``$t``-switched lambda classes, case 33 of
       ``X.3Q0`` on 2.26.40.72, 15 of ``X.3Pc`` on 2.26.39.79, 34 of ``X.3SA``
       on 2.26.37.74 -- so the hook is on that class's ``run()`` and has to
       know which case to swallow. Anchored on its three log lines, all of
       which the case must hold: the case number comes from the switch table,
       and requiring the whole trim under the label it found is the check that
       the label was the right one.

    3. **The gate** a pin goes through, and everything needed to pin without
       it. Every way of pinning a chat -- the chat list's selection mode, a
       chat's own menu, a community's -- builds the same lambda (case 9 of
       ``X.3Q7`` on 2.26.40.72, 8 of ``X.3Qq`` on 2.26.39.79) around
       ``ConversationPinHelper`` and the selected jids, and posts it to a
       background executor. Its body is ``ConversationPinHelper.pinSelectedJids``:
       count the pinned chats, compare pinned + new against the cap, and only
       then pin each new one through the app's own primitive, which writes the
       database and queues the sync mutation and has no limit of its own. The
       cap is a literal in that comparison, so the hook does the part after it
       itself, and everything it touches is read out of that one case block:

       - the lambda's own fields, by the type each is cast to on entry: the
         helper, the ``Set`` of selected jids, and the ``Function0`` run on
         success, which is what ends the selection mode;
       - the pinned map, read exactly as the gate reads it -- a field of the
         helper unwrapped by a static getter into ``ChatSettingsStore``, whose
         static ``(store, Z)Map`` answers ``getPinnedJids``;
       - the pin primitive, ``(Jid, J)Long``, the only call of that shape in the
         block, and the clock it is stamped with, the static ``(Clock)J`` read
         just before it -- WhatsApp's server-corrected time, which is what
         every other pin carries;
       - the "Chat pinned" / "Chats pinned" toast, whose strings ship with their
         names stripped, so it is the two ids around ``size == 1`` that
         identify them.

       The helper's fields are read by name from the instructions that load
       them, so a field that changes type or meaning stops the finder rather
       than hands the hook the wrong object.
    """

    BENEFIT_ANCHOR = 'PromoEligibilityManager/refreshEligibility: promo eligibility disabled'

    TRIM_ANCHORS = (
        'ConversationPinHelper/trimExcessPinnedChats no trimming needed pinned=',
        'ConversationPinHelper/trimExcessPinnedChats unpinned=',
        'ConversationPinHelper/trimExcessPinnedChats failed to unpin jid=',
    )

    GATE_ANCHORS = (
        'ConversationPinHelper/pinSelectedJids max=',
        'ConversationPinHelper/pinSelectedJids jid=',
    )

    BENEFIT_SIG_RE = re.compile(r'^\((?P<benefit>L[\w/$]+;)\)Z$')

    SET = 'Ljava/util/Set;'
    FUNCTION0 = 'Lkotlin/jvm/functions/Function0;'

    STORE_RE = re.compile(
        r'iget-object (?P<holder_reg>[vp]\d+), [vp]\d+, L(?P<helper>[\w/$]+);->(?P<holder_field>[\w$]+)'
        r':L(?P<holder>[\w/$]+);\n' + GAP
        + r'[ \t]*invoke-static \{(?P=holder_reg)\}, L(?P<getter>[\w/$]+);->(?P<getter_name>[\w$]+)'
        r'(?P<getter_sig>\(L(?P=holder);\)L(?P<store>[\w/$]+);)\n' + GAP
        + r'[ \t]*move-result-object (?P<store_reg>[vp]\d+)\n' + GAP
        + r'[ \t]*const/4 (?P<flag_reg>[vp]\d+), 0x1\n' + GAP
        + r'[ \t]*invoke-static \{(?P=store_reg), (?P=flag_reg)\}, L(?P=store);->(?P<pinned_name>[\w$]+)'
        r'(?P<pinned_sig>\(L(?P=store);Z\)Ljava/util/Map;)')

    CLOCK_RE = re.compile(
        r'invoke-static \{[vp]\d+\}, L(?P<clock>[\w/$]+);->(?P<clock_name>[\w$]+)'
        r'(?P<clock_sig>\(L(?P=clock);\)J)\n')

    PIN_RE = re.compile(
        r'invoke-virtual(?:/range)? \{[^}]*\}, L(?P<pinner>[\w/$]+);->(?P<pin_name>[\w$]+)'
        r'(?P<pin_sig>\(L[\w/$]+;J\)Ljava/lang/Long;)')

    TOAST_RE = re.compile(
        r'const (?P<reg>[vp]\d+), (?P<many>0x7f[0-9a-f]{6})\n' + GAP
        + r'[ \t]*if-ne [vp]\d+, [vp]\d+, (?P<skip>:cond_[0-9a-f]+)\n' + GAP
        + r'[ \t]*const (?P=reg), (?P<one>0x7f[0-9a-f]{6})\n' + GAP
        + r'[ \t]*(?P=skip)\n' + GAP
        + r'(?:[ \t]*const/4 [vp]\d+, 0x0\n' + GAP + r')?'
        + r'[ \t]*invoke-virtual \{[vp]\d+, (?P=reg), [vp]\d+\}, L(?P<toast>[\w/$]+);->(?P<toast_name>[\w$]+)\(II\)V')

    # How many instructions may sit between the clock read and the pin it
    # stamps: its move-result and the load of the pinner.
    CLOCK_TO_PIN = 3

    def __init__(self, args):
        super().__init__(args)
        self.is_once = True
        self.is_found = False
        self._found_benefit = False
        self._found_trim = False
        self._found_gate = False

    def class_filter(self, class_data: str) -> bool:
        return (not self._found_benefit and self.BENEFIT_ANCHOR in class_data) \
            or (not self._found_trim and self.TRIM_ANCHORS[0] in class_data) \
            or (not self._found_gate and self.GATE_ANCHORS[0] in class_data)

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        if not self._found_benefit and self.BENEFIT_ANCHOR in class_data:
            self._found_benefit = self._extract_benefit(artifacts, class_data)
        if not self._found_trim and self.TRIM_ANCHORS[0] in class_data:
            self._found_trim = self._extract_trim(artifacts, class_data)
        if not self._found_gate and self.GATE_ANCHORS[0] in class_data:
            self._found_gate = self._extract_gate(artifacts, class_data)
        self.is_found = self._found_benefit and self._found_trim and self._found_gate

    def _extract_benefit(self, artifacts: dict, class_data: str) -> bool:
        class_match = CLASS_RE.search(class_data)
        if class_match is None:
            return False
        owner = class_match.group('name')
        checks = []
        for method in METHOD_RE.finditer(class_data):
            modifiers = method.group('modifiers').split()
            sig = self.BENEFIT_SIG_RE.match(method.group('sig'))
            if sig is None or 'static' in modifiers or 'public' not in modifiers:
                continue
            body = method.group('body')
            defers = re.search(
                r'invoke-virtual \{p0, p1\}, L' + re.escape(owner) + r';->[\w$]+\('
                + re.escape(sig.group('benefit')) + r'\)Z', body)
            if defers is not None and 'Ljava/util/concurrent/ConcurrentHashMap;' in body:
                checks.append(method)
        if len(checks) != 1:
            return False
        artifacts['PIN_BENEFIT_CLASS_NAME'] = _dotted(owner)
        artifacts['PIN_BENEFIT_METHOD_NAME'] = checks[0].group('name')
        artifacts['PIN_BENEFIT_METHOD_SIG'] = checks[0].group('sig')
        return True

    @staticmethod
    def _lambda_case(class_data, anchors):
        """The anchored case of a merged lambda's ``run()``, with all its anchors under one label."""
        class_match = CLASS_RE.search(class_data)
        position = class_data.find(anchors[0])
        method = _method_at(class_data, position)
        if class_match is None or method is None:
            return None
        if 'static' in method.group('modifiers').split() or method.group('sig') != '()V':
            return None
        found = lambda_case(method.group('body'), position - method.start('body'))
        if found is None or not all(anchor in found[2] for anchor in anchors):
            return None
        return class_match.group('name'), method, found

    def _extract_trim(self, artifacts: dict, class_data: str) -> bool:
        resolved = self._lambda_case(class_data, self.TRIM_ANCHORS)
        if resolved is None:
            return False
        owner, method, (field, case, _) = resolved
        artifacts['PIN_TRIM_CLASS_NAME'] = _dotted(owner)
        artifacts['PIN_TRIM_METHOD_NAME'] = method.group('name')
        artifacts['PIN_TRIM_METHOD_SIG'] = method.group('sig')
        artifacts['PIN_TRIM_CASE_FIELD'] = field
        artifacts['PIN_TRIM_CASE'] = str(case)
        return True

    @staticmethod
    def _casts(lambda_class, block):
        """The lambda's fields, keyed by the type each is cast to on entry."""
        casts = {}
        for load in re.finditer(
                r'iget-object (?P<reg>[vp]\d+), [vp]\d+, L' + re.escape(lambda_class)
                + r';->(?P<field>[\w$]+):Ljava/lang/Object;\n' + GAP
                + r'[ \t]*check-cast (?P=reg), (?P<type>L[\w/$]+;)\n', block):
            casts.setdefault(load.group('type'), set()).add(load.group('field'))
        return casts

    @staticmethod
    def _helper_field(helper, owner_type, block):
        """The one field of the helper the block reads a value of that type from."""
        fields = set(re.findall(
            r'iget-object [vp]\d+, [vp]\d+, L' + re.escape(helper) + r';->([\w$]+):L'
            + re.escape(owner_type) + r';', block))
        return fields.pop() if len(fields) == 1 else None

    def _extract_gate(self, artifacts: dict, class_data: str) -> bool:
        resolved = self._lambda_case(class_data, self.GATE_ANCHORS)
        if resolved is None:
            return False
        lambda_class, method, (field, case, block) = resolved

        stores = list(self.STORE_RE.finditer(block))
        pins = list(self.PIN_RE.finditer(block))
        toasts = list(self.TOAST_RE.finditer(block))
        if len(stores) != 1 or len(pins) != 1 or len(toasts) != 1:
            return False
        store, pin, toast = stores[0], pins[0], toasts[0]
        clocks = [clock for clock in self.CLOCK_RE.finditer(block)
                  if clock.end() <= pin.start()
                  and len(_instructions(block[clock.end():pin.start()])) <= self.CLOCK_TO_PIN]
        if len(clocks) != 1:
            return False
        clock = clocks[0]

        helper = store.group('helper')
        casts = self._casts(lambda_class, block)
        lambda_fields = [casts.get(kind, set()) for kind in ('L%s;' % helper, self.SET, self.FUNCTION0)]
        if any(len(fields) != 1 for fields in lambda_fields):
            return False
        helper_field, selection_field, done_field = (fields.pop() for fields in lambda_fields)

        pinner_field = self._helper_field(helper, pin.group('pinner'), block)
        clock_field = self._helper_field(helper, clock.group('clock'), block)
        toast_field = self._helper_field(helper, toast.group('toast'), block)
        if None in (pinner_field, clock_field, toast_field):
            return False

        artifacts['PIN_GATE_CLASS_NAME'] = _dotted(lambda_class)
        artifacts['PIN_GATE_METHOD_NAME'] = method.group('name')
        artifacts['PIN_GATE_METHOD_SIG'] = method.group('sig')
        artifacts['PIN_GATE_CASE_FIELD'] = field
        artifacts['PIN_GATE_CASE'] = str(case)
        artifacts['PIN_GATE_HELPER_FIELD'] = helper_field
        artifacts['PIN_GATE_SELECTION_FIELD'] = selection_field
        artifacts['PIN_GATE_DONE_FIELD'] = done_field
        artifacts['PIN_HELPER_CLASS_NAME'] = _dotted(helper)
        artifacts['PIN_HELPER_STORE_FIELD'] = store.group('holder_field')
        artifacts['PIN_HELPER_PINNER_FIELD'] = pinner_field
        artifacts['PIN_HELPER_CLOCK_FIELD'] = clock_field
        artifacts['PIN_HELPER_TOAST_FIELD'] = toast_field
        artifacts['PIN_STORE_GETTER_CLASS_NAME'] = _dotted(store.group('getter'))
        artifacts['PIN_STORE_GETTER_METHOD_NAME'] = store.group('getter_name')
        artifacts['PIN_STORE_GETTER_METHOD_SIG'] = store.group('getter_sig')
        artifacts['PIN_PINNED_METHOD_NAME'] = store.group('pinned_name')
        artifacts['PIN_PINNED_METHOD_SIG'] = store.group('pinned_sig')
        artifacts['PIN_PINNER_METHOD_NAME'] = pin.group('pin_name')
        artifacts['PIN_PINNER_METHOD_SIG'] = pin.group('pin_sig')
        artifacts['PIN_CLOCK_METHOD_NAME'] = clock.group('clock_name')
        artifacts['PIN_CLOCK_METHOD_SIG'] = clock.group('clock_sig')
        artifacts['PIN_TOAST_METHOD_NAME'] = toast.group('toast_name')
        artifacts['PIN_TOAST_ONE'] = toast.group('one')
        artifacts['PIN_TOAST_MANY'] = toast.group('many')
        return True
