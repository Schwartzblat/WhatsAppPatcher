from argparse import Namespace

from artifactory_generator.contact_info import ContactInfoFinder


def builder(activity='com.whatsapp.chatinfo.ContactInfoActivity', key='jid',
            key_reg='v3', jumbo=True, name='A02'):
    """The profile intent builder, in the shape 2.26.37.74 writes it.

    Two details are deliberate and both come from the real method: the key
    register is loaded at the very top and used fifty lines later, and
    ``should_show_chat_action`` arrives as ``const-string/jumbo``.
    """
    chat_action = 'const-string/jumbo v0, "should_show_chat_action"' if jumbo \
        else 'const-string v0, "should_show_chat_action"'
    return (
        '.method public static final %s(Landroid/content/Context;'
        'Lcom/whatsapp/infra/core/jid/UserJid;ZZ)Landroid/content/Intent;\n'
        '    .locals 4\n'
        '    const-string %s, "%s"\n'
        '    new-instance v2, Landroid/content/Intent;\n'
        '    invoke-direct {v2}, Landroid/content/Intent;-><init>()V\n'
        '    invoke-virtual {p0}, Landroid/content/Context;->getPackageName()Ljava/lang/String;\n'
        '    move-result-object v1\n'
        '    const-string v0, "%s"\n'
        '    invoke-virtual {v2, v1, v0}, Landroid/content/Intent;->'
        'setClassName(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;\n'
        '    move-result-object v2\n'
        '    invoke-virtual {p1}, Lcom/whatsapp/infra/core/jid/Jid;->'
        'getRawString()Ljava/lang/String;\n'
        '    move-result-object v0\n'
        '    invoke-virtual {v2, %s, v0}, Landroid/content/Intent;->'
        'putExtra(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;\n'
        '    const-string v0, "circular_transition"\n'
        '    invoke-virtual {v2, v0, p2}, Landroid/content/Intent;->'
        'putExtra(Ljava/lang/String;Z)Landroid/content/Intent;\n'
        '    %s\n'
        '    invoke-virtual {v2, v0, p3}, Landroid/content/Intent;->'
        'putExtra(Ljava/lang/String;Z)Landroid/content/Intent;\n'
        '    return-object v2\n'
        '.end method\n' % (name, key_reg, key, activity, key_reg, chat_action))


def wrap(*methods, cls='LX/1Ob;'):
    return '.class public final %s\n.super Ljava/lang/Object;\n\n' % cls + '\n'.join(methods)


# A second builder in the same class, for a different screen, carrying a
# setClassName and a jid but none of the profile screen's own extras.
OTHER_BUILDER = (
    '.method public static A04(Landroid/content/Context;'
    'Lcom/whatsapp/infra/core/jid/UserJid;)Landroid/content/Intent;\n'
    '    .locals 3\n'
    '    const-string v1, "jid"\n'
    '    new-instance v2, Landroid/content/Intent;\n'
    '    invoke-direct {v2}, Landroid/content/Intent;-><init>()V\n'
    '    invoke-virtual {p0}, Landroid/content/Context;->getPackageName()Ljava/lang/String;\n'
    '    move-result-object v0\n'
    '    const-string v3, "com.whatsapp.Conversation"\n'
    '    invoke-virtual {v2, v0, v3}, Landroid/content/Intent;->'
    'setClassName(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;\n'
    '    return-object v2\n'
    '.end method\n')


def finder():
    return ContactInfoFinder(Namespace(apk_path='x.apk', temp_path='./temp'))


def run(content):
    found = finder()
    artifacts = {}
    if found.class_filter(content):
        found.extract_artifacts(artifacts, content)
    return artifacts, found.is_found


def test_reads_the_activity_and_the_jid_key():
    artifacts, is_found = run(wrap(builder()))

    assert is_found
    assert artifacts['CONTACT_INFO_ACTIVITY_CLASS_NAME'] == \
        'com.whatsapp.chatinfo.ContactInfoActivity'
    assert artifacts['CONTACT_INFO_JID_EXTRA'] == 'jid'


def test_follows_the_activity_to_another_package():
    # The whole reason the name is not written down: group info already moved
    # from com.whatsapp.groupinfo to com.whatsapp.chatinfo.group.
    artifacts, is_found = run(wrap(builder(activity='com.whatsapp.contactinfo.ui.ContactInfo')))

    assert is_found
    assert artifacts['CONTACT_INFO_ACTIVITY_CLASS_NAME'] == \
        'com.whatsapp.contactinfo.ui.ContactInfo'


def test_reads_a_renamed_jid_key():
    artifacts, is_found = run(wrap(builder(key='user_jid')))

    assert is_found
    assert artifacts['CONTACT_INFO_JID_EXTRA'] == 'user_jid'


def test_reads_the_key_register_loaded_far_above_its_use():
    # The real method loads the key at the top and puts it fifty lines later,
    # so anything that takes the nearest const-string reads "circular_transition".
    artifacts, _ = run(wrap(builder()))

    assert artifacts['CONTACT_INFO_JID_EXTRA'] == 'jid'


def test_takes_the_nearest_assignment_when_the_register_is_reused():
    method = builder().replace(
        '    invoke-virtual {p1}, Lcom/whatsapp/infra/core/jid/Jid;->',
        '    const-string v3, "jid"\n'
        '    invoke-virtual {p1}, Lcom/whatsapp/infra/core/jid/Jid;->')
    method = method.replace('    const-string v3, "jid"\n    new-instance',
                            '    const-string v3, "stale"\n    new-instance', 1)
    artifacts, is_found = run(wrap(method))

    assert is_found
    assert artifacts['CONTACT_INFO_JID_EXTRA'] == 'jid'


def test_a_jumbo_marker_still_matches():
    plain, _ = run(wrap(builder(jumbo=False)))
    jumbo, _ = run(wrap(builder(jumbo=True)))

    assert plain == jumbo
    assert jumbo['CONTACT_INFO_JID_EXTRA'] == 'jid'


def test_another_screens_builder_in_the_same_class_is_ignored():
    artifacts, is_found = run(wrap(OTHER_BUILDER, builder()))

    assert is_found
    assert artifacts['CONTACT_INFO_ACTIVITY_CLASS_NAME'] == \
        'com.whatsapp.chatinfo.ContactInfoActivity'


def test_a_class_without_the_markers_does_not_fire():
    artifacts, is_found = run(wrap(OTHER_BUILDER))

    assert artifacts == {}
    assert not is_found


def test_two_candidate_builders_decline():
    # Ambiguity means do not fire: picking one of two would key the whole
    # feature on a screen nobody checked.
    artifacts, is_found = run(wrap(builder(name='A02'),
                                   builder(name='A03', activity='com.whatsapp.Other')))

    assert artifacts == {}
    assert not is_found
