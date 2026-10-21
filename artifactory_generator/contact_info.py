import re

from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder


class ContactInfoFinder(SimpleArtifactoryFinder):
    """Finds how WhatsApp opens somebody's profile.

    The Shared members screen hands a person off to WhatsApp's own contact
    info screen, and WhatsApp builds that intent itself in one place::

        new Intent().setClassName(context.getPackageName(),
                                  "com.whatsapp.chatinfo.ContactInfoActivity")
                    .putExtra("jid", userJid.getRawString())
                    .putExtra("circular_transition", ...)
                    .putExtra("should_show_chat_action", ...)

    Neither value is written down here. The class name is unobfuscated, but
    unobfuscated has never meant fixed: ``GroupChatInfoActivity`` moved from
    ``com/whatsapp/groupinfo/`` to ``com/whatsapp/chatinfo/group/`` between
    releases, and a moved activity is an ``ActivityNotFoundException`` at the
    one moment the user taps a row. The extra key is the other half of the
    same bet, and getting it wrong opens the screen on nobody.

    So both are read out of the builder, which is identified by its *shape*
    rather than by the name it carries -- a method holding a
    ``setClassName`` and the two extras only this screen takes,
    ``circular_transition`` and ``should_show_chat_action``. A release that
    moves the class moves the literal with it, and the finder follows.

    R8 outlines parts of the builder from 2.26.39: the jid through a static
    ``(Intent, Jid, key)V`` helper, and on some releases the ``setClassName``
    through an ``(Intent, String)Intent`` one. Both are read through. Verified
    on 2.26.36.74, 2.26.37.74, 2.26.39.79, 2.26.40.72, 2.26.41.71 and 2.26.41.78.

    The two remaining extras are not emitted because they are not needed:
    ``should_show_chat_action`` defaults to true (which is what puts the
    Message button on the screen) and ``circular_transition`` to false.
    """

    #: Both markers, allowing the jumbo form. ``should_show_chat_action`` is
    #: emitted as ``const-string/jumbo`` on 2.26.37.74 -- a plain
    #: ``const-string`` pattern misses it and the finder never fires.
    CIRCULAR_RE = re.compile(r'const-string(?:/jumbo)? [vp]\d+, "circular_transition"')
    CHAT_ACTION_RE = re.compile(r'const-string(?:/jumbo)? [vp]\d+, "should_show_chat_action"')

    METHOD_RE = re.compile(r'^\.method (?P<header>[^\n]*)\n(?P<body>.*?)^\.end method', re.M | re.S)

    SET_CLASS_NAME_RE = re.compile(
        r'invoke-virtual \{(?P<regs>[^}]*)\}, Landroid/content/Intent;->'
        r'setClassName\(Ljava/lang/String;Ljava/lang/String;\)')

    #: R8 outlines the setClassName in some releases into a static helper that
    #: takes the intent and the class name (``X.8YX.A08`` on 2.26.41.71).
    SET_CLASS_NAME_HELPER_RE = re.compile(
        r'invoke-static \{(?P<regs>[^}]*)\}, L[^;\s]+;->[\w$]+'
        r'\(Landroid/content/Intent;Ljava/lang/String;\)Landroid/content/Intent;')

    #: From 2.26.39 the jid goes in through a static helper that calls
    #: getRawString itself, ``(Intent, Jid, key)V`` (``X.22S.A1R`` on
    #: 2.26.41.78). The same helper files the group's jid too, from another
    #: parameter, so the call that counts is the one handed the UserJid.
    JID_HELPER_RE = re.compile(
        r'invoke-static \{(?P<regs>[^}]*)\}, L[^;\s]+;->[\w$]+'
        r'\(Landroid/content/Intent;Lcom/whatsapp/infra/core/jid/\w+;Ljava/lang/String;\)V')

    USER_JID = 'Lcom/whatsapp/infra/core/jid/UserJid;'
    PARAM_RE = re.compile(r'\[*(?:[ZBSCIFJD]|L[^;]+;)')

    #: ``getRawString`` on anything: the builder calls it on Jid, but the
    #: declaring type is the app's and may be renamed around it.
    RAW_STRING_RE = re.compile(
        r'invoke-virtual \{[^}]*\}, L[^;]+;->getRawString\(\)Ljava/lang/String;\s*\n'
        r'(?:\s*\.line \d+\s*\n)*'
        r'\s*move-result-object (?P<value>[vp]\d+)')

    PUT_EXTRA_RE = re.compile(
        r'invoke-virtual \{(?P<regs>[^}]*)\}, Landroid/content/Intent;->'
        r'putExtra\(Ljava/lang/String;Ljava/lang/String;\)')

    def __init__(self, args):
        super().__init__(args)
        self.is_once = True
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        # Not 'setClassName' as well: on 2.26.41.71 the builder only calls the
        # helper it was outlined into.
        return (self.CIRCULAR_RE.search(class_data) is not None
                and self.CHAT_ACTION_RE.search(class_data) is not None)

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        matches = []
        for method in self.METHOD_RE.finditer(class_data):
            body = method.group('body')
            # Both extras in the *same* method, not merely in the same file:
            # a class can hold several intent builders, and only this one is
            # the profile screen's.
            if not self.CIRCULAR_RE.search(body) or not self.CHAT_ACTION_RE.search(body):
                continue
            found = self._read(method.group('header'), body)
            if found is not None:
                matches.append(found)
        # Ambiguity means do not fire: picking one of two builders would key
        # the whole row on a screen nobody checked.
        if len(matches) != 1:
            return
        activity, jid_extra = matches[0]
        artifacts['CONTACT_INFO_ACTIVITY_CLASS_NAME'] = activity
        artifacts['CONTACT_INFO_JID_EXTRA'] = jid_extra
        self.is_found = True

    def _read(self, header: str, body: str):
        """The activity this method names and the key it files the jid under."""
        activity = self._activity(body)
        jid_extra = self._jid_extra(body)
        if jid_extra is None:
            jid_extra = self._helper_jid_extra(header, body)
        if activity is None or jid_extra is None:
            return None
        return activity, jid_extra

    def _activity(self, body: str):
        """The second argument of ``setClassName``, which is the class name."""
        match = self.SET_CLASS_NAME_RE.search(body)
        if match is not None:
            registers = [reg.strip() for reg in match.group('regs').split(',')]
            # {receiver, packageName, className}
            if len(registers) != 3:
                return None
            name = self._string_in(body, registers[2], match.start())
        else:
            match = self.SET_CLASS_NAME_HELPER_RE.search(body)
            if match is None:
                return None
            registers = [reg.strip() for reg in match.group('regs').split(',')]
            # {intent, className}
            if len(registers) != 2:
                return None
            name = self._string_in(body, registers[1], match.start())
        # A class name, not some other string that happened to be in the
        # register: dotted, and nothing a name cannot contain.
        if name is None or '.' not in name or not re.fullmatch(r'[\w.$]+', name):
            return None
        return name

    def _jid_extra(self, body: str):
        """The key of the first ``putExtra`` that files a ``getRawString`` result.

        Located through the value rather than by position, because the key is
        loaded into its register at the top of the method and used some fifty
        instructions later -- the const-string nearest the call is
        ``circular_transition``.
        """
        raw = self.RAW_STRING_RE.search(body)
        if raw is None:
            return None
        value = raw.group('value')
        for put in self.PUT_EXTRA_RE.finditer(body, raw.end()):
            registers = [reg.strip() for reg in put.group('regs').split(',')]
            # {receiver, key, value}
            if len(registers) != 3 or registers[2] != value:
                continue
            return self._string_in(body, registers[1], put.start())
        return None

    def _helper_jid_extra(self, header: str, body: str):
        """The key the outlined jid helper is given beside the UserJid parameter."""
        register = self._user_jid_register(header)
        if register is None:
            return None
        keys = set()
        for call in self.JID_HELPER_RE.finditer(body):
            registers = [reg.strip() for reg in call.group('regs').split(',')]
            # {intent, jid, key}
            if len(registers) == 3 and registers[1] == register:
                keys.add(self._string_in(body, registers[2], call.start()))
        return keys.pop() if len(keys) == 1 else None

    def _user_jid_register(self, header: str):
        """The p-register of the method's one UserJid parameter, or None."""
        match = re.match(r'(?P<modifiers>[\w ]*?)[\w$<>]+\((?P<params>[^)]*)\)', header)
        if match is None:
            return None
        index = 0 if 'static' in match.group('modifiers').split() else 1
        found = []
        for param in self.PARAM_RE.findall(match.group('params')):
            if param == self.USER_JID:
                found.append('p%d' % index)
            index += 2 if param in ('J', 'D') else 1
        return found[0] if len(found) == 1 else None

    @staticmethod
    def _string_in(body: str, register: str, before: int):
        """What {register} last held before {before}, if a const-string put it there.

        The *last* assignment, not the first: a register is reused, and the
        one that matters is whichever load the call actually sees.
        """
        loads = re.finditer(
            r'const-string(?:/jumbo)? %s, "(?P<value>[^"]*)"' % re.escape(register), body)
        value = None
        for load in loads:
            if load.start() >= before:
                break
            value = load.group('value')
        return value
