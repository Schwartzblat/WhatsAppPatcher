from argparse import Namespace

from artifactory_generator.meta_ai_fab import MetaAiFabFinder

# The Meta AI fab delegate. "WAAI.FAB" is the presentation source it stamps on
# the intent it launches, so it reaches the server and cannot be renamed.
DELEGATE = ('.class public final LX/1YX;\n.super Ljava/lang/Object;\n\n'
            '.method public final A06(IZ)V\n'
            '    .registers 4\n'
            '    const-string v1, "WAAI.FAB"\n'
            '    return-void\n'
            '.end method\n')

# The gate: reads flags and fields off the delegate, calls nothing on it.
GATE = ('.method public CRj()Z\n'
        '    .registers 6\n'
        '    iget-object v0, p0, Lcom/whatsapp/conversationslist/ConversationsFragment;->A0a:LX/00s;\n'
        '    invoke-interface {v0}, LX/00s;->get()Ljava/lang/Object;\n'
        '    move-result-object v4\n'
        '    check-cast v4, LX/1YX;\n'
        '    iget-object v0, v4, LX/1YX;->A0B:LX/05C;\n'
        '    invoke-static {v4}, LX/1YX;->A00(LX/1YX;)LX/IrI;\n'
        '    const/4 v2, 0x0\n'
        '    return v2\n'
        '.end method\n')

# The fab's long-press handler: same shape, same delegate, but it launches Meta
# AI instead of answering a question. This is the method the finder must not
# mistake for the gate.
LONG_PRESS = ('.method public ByD()Z\n'
              '    .registers 6\n'
              '    iget-object v0, p0, Lcom/whatsapp/conversationslist/ConversationsFragment;->A0a:LX/00s;\n'
              '    invoke-interface {v0}, LX/00s;->get()Ljava/lang/Object;\n'
              '    move-result-object v5\n'
              '    check-cast v5, LX/1YX;\n'
              '    const/16 v0, 0xa\n'
              '    const/4 v4, 0x1\n'
              '    invoke-virtual {v5, v0, v4}, LX/1YX;->A06(IZ)V\n'
              '    return v4\n'
              '.end method\n')

# The tab-switch callback. Unobfuscated parameter type, no delegate reference.
ON_FAB = ('.method public CZK(Lcom/whatsapp/home/ExtendedMiniFab;)V\n'
          '    .registers 2\n'
          '    return-void\n'
          '.end method\n')

FRAGMENT_HEADER = ('.class public Lcom/whatsapp/conversationslist/ConversationsFragment;\n'
                   '.super Lcom/whatsapp/ui/coreui/fragments/WaFragment;\n\n')

FRAGMENT = FRAGMENT_HEADER + ON_FAB + LONG_PRESS + GATE


def finder():
    return MetaAiFabFinder(Namespace(apk_path='x.apk', temp_path='./temp'))


def feed(found, *classes):
    artifacts = {}
    for class_data in classes:
        if found.class_filter(class_data):
            found.extract_artifacts(artifacts, class_data)
    return artifacts


def test_fires_on_the_chat_list_gate():
    found = finder()
    artifacts = feed(found, DELEGATE, FRAGMENT)
    assert found.is_found is True
    assert artifacts['META_AI_FAB_GATE_CLASS_NAME'] == 'com.whatsapp.conversationslist.ConversationsFragment'
    assert artifacts['META_AI_FAB_GATE_METHOD_NAME'] == 'CRj'
    assert artifacts['META_AI_FAB_GATE_METHOD_SIG'] == '()Z'


def test_fires_when_the_fragment_is_scanned_before_the_delegate():
    """Files arrive in filesystem order, so neither half may assume it is first."""
    found = finder()
    artifacts = feed(found, FRAGMENT, DELEGATE)
    assert found.is_found is True
    assert artifacts['META_AI_FAB_GATE_METHOD_NAME'] == 'CRj'


def test_does_not_pick_the_long_press_handler():
    """The regression guard for the one real ambiguity in this class.

    Both methods are public ()Z and both reach the delegate; only the handler
    calls anything on it. Picking it would hook the button's long press and
    leave the button itself exactly where it was."""
    found = finder()
    artifacts = feed(found, DELEGATE, FRAGMENT_HEADER + LONG_PRESS)
    assert found.is_found is False
    assert artifacts == {}


def test_does_not_fire_when_two_pure_predicates_reach_the_delegate():
    ambiguous = FRAGMENT + GATE.replace('CRj', 'CRk')
    found = finder()
    artifacts = feed(found, DELEGATE, ambiguous)
    assert found.is_found is False
    assert artifacts == {}


def test_ignores_another_chat_list_screen():
    """Only the class the home tab uses is anchored; its subclasses inherit the
    gate rather than declaring one, and an unrelated fragment must not match."""
    other = FRAGMENT.replace('conversationslist/ConversationsFragment',
                             'conversation/conversationslist/FolderConversationsFragment')
    found = finder()
    artifacts = feed(found, DELEGATE, other)
    assert found.is_found is False
    assert artifacts == {}


def test_survives_a_renamed_delegate_and_gate():
    moved_delegate = DELEGATE.replace('1YX', '1SP')
    moved_fragment = FRAGMENT.replace('1YX', '1SP').replace('CRj', 'CMG')
    found = finder()
    artifacts = feed(found, moved_delegate, moved_fragment)
    assert found.is_found is True
    assert artifacts['META_AI_FAB_GATE_METHOD_NAME'] == 'CMG'


def test_reads_a_jumbo_origin_string():
    """Recent builds use const-string/jumbo for large string indices."""
    jumbo = DELEGATE.replace('const-string v1,', 'const-string/jumbo v1,')
    found = finder()
    artifacts = feed(found, jumbo, FRAGMENT)
    assert found.is_found is True
    assert artifacts['META_AI_FAB_GATE_METHOD_NAME'] == 'CRj'


def test_does_not_fire_on_the_delegate_alone():
    found = finder()
    artifacts = feed(found, DELEGATE)
    assert found.is_found is False
    assert artifacts == {}
