from argparse import Namespace

from artifactory_generator.meta_ai_button import MetaAiButtonFinder

CHATS_CLASS = 'Lcom/whatsapp/conversationslist/ConversationsFragment;'
CALLS_CLASS = 'Lcom/whatsapp/calling/ui/callhistory/view/CallsHistoryFragment;'


def ring(gate='LX/1QX;', method='A06'):
    """The secondary fab's drawable, as baksmali writes it: the fragment check, then the gate."""
    return ('.method public B52(Landroid/view/View;)Landroid/graphics/drawable/Drawable;\n'
            '    .locals 5\n'
            '    .line 0\n'
            '    check-cast v1, Landroidx/fragment/app/Fragment;\n'
            '    const/4 v3, 0x0\n'
            '    if-eqz v1, :cond_1\n'
            '    invoke-virtual {v1}, Landroidx/fragment/app/Fragment;->A1f()Z\n'
            '    move-result v0\n'
            '    if-eqz v0, :cond_1\n'
            '    invoke-interface {v0}, LX/00w;->get()Ljava/lang/Object;\n'
            '    move-result-object v0\n'
            '    .line 12\n'
            '    check-cast v0, %s\n'
            '    .line 13\n'
            '\n'
            '    invoke-virtual {v0}, %s->%s()Z\n'
            '    move-result v0\n'
            '    if-eqz v0, :cond_1\n'
            '    :cond_1\n'
            '    return-object v3\n'
            '.end method\n' % (gate, gate, method))


def calls_gate(name='A0c', gate='LX/1QX;'):
    """The calls tab's own copy of the check: it casts to the shared gate class and reads it inline."""
    return ('.method private final %s()Z\n'
            '    .locals 2\n'
            '    iget-object v0, p0, %s->A0u:LX/05Z;\n'
            '    invoke-static {v0}, LX/05Z;->A03(LX/05Z;)Ljava/lang/Object;\n'
            '    move-result-object v1\n'
            '    check-cast v1, %s\n'
            '    iget-object v0, v1, %s->A05:LX/05Z;\n'
            '    invoke-static {v0}, LX/26u;->A1W(LX/05Z;)Z\n'
            '    move-result v0\n'
            '    return v0\n'
            '.end method\n' % (name, CALLS_CLASS, gate, gate))


OTHER_PREDICATE = ('.method public A0d()Z\n'
                   '    .locals 2\n'
                   '    check-cast v1, LX/0yt;\n'
                   '    invoke-virtual {v1}, LX/0yt;->A02()Z\n'
                   '    move-result v0\n'
                   '    return v0\n'
                   '.end method\n')

CHATS = ('.class public Lcom/whatsapp/conversationslist/ConversationsFragment;\n'
         '.super Lcom/whatsapp/ui/coreui/fragments/WaFragment;\n\n'
         + ring() + OTHER_PREDICATE)

CALLS = ('.class public Lcom/whatsapp/calling/ui/callhistory/view/CallsHistoryFragment;\n'
         '.super Lcom/whatsapp/ui/coreui/fragments/WaFragment;\n\n'
         + calls_gate() + OTHER_PREDICATE)

# The one-method class the gate lived in up to 2.26.41: it read the Settings >
# Chats switch, which 2.26.42 retired (its daily cron now deletes the key).
OLD_GATE_CLASS = ('.class public final LX/1LE;\n.super Ljava/lang/Object;\n\n'
                  '.method public final A00()Z\n    .registers 4\n'
                  '    const-string v1, "bonsai_meta_ai_button_setting_enabled"\n'
                  '    return v0\n.end method\n')


def finder():
    return MetaAiButtonFinder(Namespace(apk_path='x.apk', temp_path='./temp'))


def feed(found, *classes):
    artifacts = {}
    for class_data in classes:
        if found.class_filter(class_data):
            found.extract_artifacts(artifacts, class_data)
    return artifacts


def test_finds_both_gates_from_the_chat_list_and_the_calls_tab():
    found = finder()
    artifacts = feed(found, CHATS, CALLS)
    assert found.is_found is True
    assert artifacts['META_AI_BUTTON_GATE_CLASS_NAME'] == 'X.1QX'
    assert artifacts['META_AI_BUTTON_GATE_METHOD_NAME'] == 'A06'
    assert artifacts['META_AI_BUTTON_GATE_METHOD_SIG'] == '()Z'
    assert artifacts['META_AI_CALLS_GATE_CLASS_NAME'] == (
        'com.whatsapp.calling.ui.callhistory.view.CallsHistoryFragment')
    assert artifacts['META_AI_CALLS_GATE_METHOD_NAME'] == 'A0c'
    assert artifacts['META_AI_CALLS_GATE_METHOD_SIG'] == '()Z'


def test_the_calls_tab_may_arrive_first():
    """Files come in filesystem order, and the calls fragment is in a later dex on
    some builds and an earlier one on others."""
    found = finder()
    artifacts = feed(found, CALLS, CHATS)
    assert found.is_found is True
    assert artifacts['META_AI_CALLS_GATE_METHOD_NAME'] == 'A0c'
    assert artifacts['META_AI_BUTTON_GATE_CLASS_NAME'] == 'X.1QX'


def test_the_chats_gate_is_written_before_the_calls_tab_is_seen():
    found = finder()
    artifacts = feed(found, CHATS)
    assert found.is_found is False
    assert artifacts['META_AI_BUTTON_GATE_CLASS_NAME'] == 'X.1QX'
    assert 'META_AI_CALLS_GATE_METHOD_NAME' not in artifacts


def test_finds_the_gate_as_it_was_before_2_26_42():
    """The same rule resolves the old one-method gate, so one rule serves every build."""
    chats = CHATS.replace(ring(), ring('LX/1LE;', 'A00'))
    calls = CALLS.replace(calls_gate(), calls_gate('A0W', 'LX/1LE;'))
    found = finder()
    artifacts = feed(found, OLD_GATE_CLASS, chats, calls)
    assert found.is_found is True
    assert artifacts['META_AI_BUTTON_GATE_CLASS_NAME'] == 'X.1LE'
    assert artifacts['META_AI_BUTTON_GATE_METHOD_NAME'] == 'A00'
    assert artifacts['META_AI_CALLS_GATE_METHOD_NAME'] == 'A0W'


def test_ignores_the_fragment_check_in_the_ring_method():
    """The androidx Fragment's own ()Z is asked first in the same method; it is not
    the gate and nothing hooks it."""
    only_fragment = CHATS.replace(ring(), ring().replace(
        '    check-cast v0, LX/1QX;\n', '').replace('LX/1QX;->A06()Z', 'Landroidx/fragment/app/Fragment;->A1g()Z'))
    found = finder()
    artifacts = feed(found, only_fragment, CALLS)
    assert found.is_found is False
    assert artifacts == {}


def test_does_not_fire_when_the_ring_method_asks_two_gates():
    doubled = CHATS.replace('    move-result v0\n    if-eqz v0, :cond_1\n    :cond_1\n',
                            '    move-result v0\n    check-cast v2, LX/1Qb;\n    invoke-virtual {v2}, LX/1Qb;->A00()Z\n'
                            '    :cond_1\n')
    found = finder()
    artifacts = feed(found, doubled, CALLS)
    assert found.is_found is False
    assert artifacts == {}


def test_does_not_fire_without_the_ring_method():
    """No secondary fab drawable means no button this rule can vouch for."""
    bare = ('.class public Lcom/whatsapp/conversationslist/ConversationsFragment;\n'
            '.super Lcom/whatsapp/ui/coreui/fragments/WaFragment;\n\n' + OTHER_PREDICATE)
    found = finder()
    artifacts = feed(found, bare, CALLS)
    assert found.is_found is False
    assert artifacts == {}


def test_does_not_name_a_calls_gate_when_two_methods_cast_to_the_gate_class():
    ambiguous = CALLS + calls_gate('A0e')
    found = finder()
    artifacts = feed(found, CHATS, ambiguous)
    assert found.is_found is False
    assert 'META_AI_CALLS_GATE_METHOD_NAME' not in artifacts
    assert artifacts['META_AI_BUTTON_GATE_METHOD_NAME'] == 'A06'


def test_reads_a_gate_class_whose_name_has_an_l_in_it():
    """2.26.37.74 named it X.1LK, and stitch's greedy class regex read that as K:
    the key was written, the gate passed, and the hook died on ClassNotFoundException."""
    chats = CHATS.replace('LX/1QX;', 'LX/1LK;')
    calls = CALLS.replace('LX/1QX;', 'LX/1LK;')
    found = finder()
    artifacts = feed(found, chats, calls)
    assert found.is_found is True
    assert artifacts['META_AI_BUTTON_GATE_CLASS_NAME'] == 'X.1LK'


def test_ignores_other_classes():
    found = finder()
    assert found.class_filter(OLD_GATE_CLASS) is False
