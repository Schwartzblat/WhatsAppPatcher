from argparse import Namespace

from artifactory_generator.meta_ai_button import MetaAiButtonFinder, MetaAiCallsButtonFinder

PREF = 'bonsai_meta_ai_button_setting_enabled'


def reads_pref(name, modifiers='public final '):
    return ('.method %s%s()Z\n'
            '    .registers 4\n'
            '    invoke-virtual {v0}, LX/0Q7;->A09()Z\n'
            '    move-result v0\n'
            '    if-eqz v0, :cond_0\n'
            '    const-string v1, "%s"\n'
            '    const/4 v2, 0x1\n'
            '    invoke-interface {v0, v1, v2}, Landroid/content/SharedPreferences;'
            '->getBoolean(Ljava/lang/String;Z)Z\n'
            '    move-result v0\n'
            '    return v0\n'
            '    :cond_0\n'
            '    const/4 v0, 0x0\n'
            '    return v0\n'
            '.end method\n' % (modifiers, name, PREF))


# The gate: a class that exists to answer this one question.
GATE = ('.class public final LX/13z;\n.super Ljava/lang/Object;\n\n'
        '.field public final A00:LX/05D;\n\n'
        '.field public final A01:LX/05D;\n\n'
        + reads_pref('A00'))

# The calls tab asks the same question with its own copy of the check.
CALLS = ('.class public Lcom/whatsapp/calling/ui/callhistory/view/CallsHistoryFragment;\n'
         '.super Lcom/whatsapp/ui/coreui/fragments/WaFragment;\n\n'
         + reads_pref('A0Z', 'private final ')
         + '.method public Azb()Ljava/lang/Integer;\n    .registers 2\n'
           '    const/4 v0, 0x0\n    return-object v0\n.end method\n')

# WhatsApp's own Settings > Chats screen reads the same key to draw its switch.
SETTINGS = ('.class public Lcom/whatsapp/settings/ui/SettingsChat;\n'
            '.super Lcom/whatsapp/ui/coreui/activities/WaActivity;\n\n'
            '.method public onCreate(Landroid/os/Bundle;)V\n'
            '    .registers 4\n'
            '    const-string v1, "%s"\n'
            '    invoke-interface {v0, v1, v2}, Landroid/content/SharedPreferences;'
            '->getBoolean(Ljava/lang/String;Z)Z\n'
            '    return-void\n'
            '.end method\n' % PREF)


def gate_finder():
    return MetaAiButtonFinder(Namespace(apk_path='x.apk', temp_path='./temp'))


def calls_finder():
    return MetaAiCallsButtonFinder(Namespace(apk_path='x.apk', temp_path='./temp'))


def feed(found, *classes):
    artifacts = {}
    for class_data in classes:
        if found.class_filter(class_data):
            found.extract_artifacts(artifacts, class_data)
    return artifacts


def test_fires_on_the_button_gate():
    found = gate_finder()
    artifacts = feed(found, SETTINGS, CALLS, GATE)
    assert found.is_found is True
    assert artifacts['META_AI_BUTTON_GATE_CLASS_NAME'] == 'X.13z'
    assert artifacts['META_AI_BUTTON_GATE_METHOD_NAME'] == 'A00'
    assert artifacts['META_AI_BUTTON_GATE_METHOD_SIG'] == '()Z'


def test_does_not_fire_on_the_settings_screen_that_writes_the_setting():
    """WhatsApp's own switch reads the same key; hooking it would make that
    screen show a value the user never chose."""
    found = gate_finder()
    artifacts = feed(found, SETTINGS)
    assert found.is_found is False
    assert artifacts == {}


def test_does_not_fire_on_a_screen_that_merely_asks():
    """The regression guard for the defect this finder fixes.

    The calls fragment reads the same key inside a class of a hundred methods.
    Pinning a caller rather than the gate is how the first version of this hook
    covered one of four entry points and looked like it worked."""
    found = gate_finder()
    artifacts = feed(found, CALLS)
    assert found.is_found is False
    assert artifacts == {}


def test_does_not_fire_when_the_gate_class_grew_a_second_method():
    """Two methods means the class is no longer only this question, so which one
    is the gate is a guess."""
    grown = GATE + reads_pref('A01')
    found = gate_finder()
    artifacts = feed(found, grown)
    assert found.is_found is False
    assert artifacts == {}


def test_survives_a_renamed_gate_class_and_method():
    moved = GATE.replace('LX/13z;', 'LX/12O;').replace('A00()Z', 'A02()Z')
    found = gate_finder()
    artifacts = feed(found, moved)
    assert found.is_found is True
    assert artifacts['META_AI_BUTTON_GATE_CLASS_NAME'] == 'X.12O'
    assert artifacts['META_AI_BUTTON_GATE_METHOD_NAME'] == 'A02'


def test_reads_a_jumbo_pref_string():
    """Recent builds use const-string/jumbo for large string indices."""
    jumbo = GATE.replace('const-string v1,', 'const-string/jumbo v1,')
    found = gate_finder()
    artifacts = feed(found, jumbo)
    assert found.is_found is True
    assert artifacts['META_AI_BUTTON_GATE_METHOD_NAME'] == 'A00'


def test_fires_on_the_calls_gate():
    found = calls_finder()
    artifacts = feed(found, GATE, SETTINGS, CALLS)
    assert found.is_found is True
    assert artifacts['META_AI_CALLS_GATE_CLASS_NAME'] == (
        'com.whatsapp.calling.ui.callhistory.view.CallsHistoryFragment')
    assert artifacts['META_AI_CALLS_GATE_METHOD_NAME'] == 'A0Z'
    assert artifacts['META_AI_CALLS_GATE_METHOD_SIG'] == '()Z'


def test_calls_finder_ignores_the_shared_gate():
    found = calls_finder()
    artifacts = feed(found, GATE, SETTINGS)
    assert found.is_found is False
    assert artifacts == {}


def test_calls_finder_does_not_fire_when_the_check_is_ambiguous():
    ambiguous = CALLS + reads_pref('A0a', 'private final ')
    found = calls_finder()
    artifacts = feed(found, ambiguous)
    assert found.is_found is False
    assert artifacts == {}
