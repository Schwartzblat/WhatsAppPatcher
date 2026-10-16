from argparse import Namespace

from artifactory_generator.pinned_chats import PinnedChatsFinder


def make():
    return PinnedChatsFinder(Namespace(apk_path='x.apk', temp_path='./temp'))


def feed(found, *classes):
    artifacts = {}
    for class_data in classes:
        if found.class_filter(class_data):
            found.extract_artifacts(artifacts, class_data)
    return artifacts


def lambda_class(name, cases, table):
    """A merged lambda class: `run()` switching on `$t` into labelled blocks.

    ``cases`` maps a label to its block; ``table`` lists the label each case
    number jumps to, in order from 0.
    """
    blocks = ''.join('    %s\n%s    return-void\n\n' % (label, block) for label, block in cases.items())
    return ('.class public final L%s;\n'
            '.super Ljava/lang/Object;\n'
            '.implements Ljava/lang/Runnable;\n\n'
            '.field public final synthetic $t:I\n\n'
            '.method public final run()V\n'
            '    .locals 26\n\n'
            '    move-object/from16 v0, p0\n\n'
            '    .line 1\n'
            '    iget v1, v0, L%s;->$t:I\n\n'
            '    .line 2\n'
            '    packed-switch v1, :pswitch_data_0\n\n'
            '%s'
            '    :pswitch_data_0\n'
            '    .packed-switch 0x0\n'
            '%s'
            '    .end packed-switch\n'
            '.end method\n' % (name, name, blocks, ''.join('        %s\n' % label for label in table)))


# pinSelectedJids as apktool writes it on 2.26.40.72, with what lies between
# the parts the finder reads left out.
GATE = ('    iget-object v5, v0, LX/3Q7;->A00:Ljava/lang/Object;\n\n'
        '    check-cast v5, LX/1JQ;\n\n'
        '    iget-object v7, v0, LX/3Q7;->A01:Ljava/lang/Object;\n\n'
        '    check-cast v7, Ljava/util/Set;\n\n'
        '    iget-object v4, v0, LX/3Q7;->A02:Ljava/lang/Object;\n\n'
        '    check-cast v4, LX/Ho6;\n\n'
        '    iget-object v11, v0, LX/3Q7;->A03:Ljava/lang/Object;\n\n'
        '    iget-object v14, v0, LX/3Q7;->A04:Ljava/lang/Object;\n\n'
        '    check-cast v14, Lkotlin/jvm/functions/Function0;\n\n'
        '    .line 785\n'
        '    iget-object v0, v5, LX/1JQ;->A02:LX/05W;\n\n'
        '    .line 786\n'
        '    invoke-static {v0}, LX/20o;->A0m(LX/05W;)LX/0Xg;\n\n'
        '    move-result-object v1\n\n'
        '    const/4 v0, 0x1\n\n'
        '    invoke-static {v1, v0}, LX/0Xg;->A09(LX/0Xg;Z)Ljava/util/Map;\n\n'
        '    move-result-object v0\n\n'
        '    const-string v1, "ConversationPinHelper/pinSelectedJids max="\n\n'
        '    iget-object v2, v5, LX/1JQ;->A0D:LX/0KO;\n\n'
        '    invoke-virtual {v2, v0, v7}, LX/0KO;->A0M(Ljava/lang/CharSequence;I)V\n\n'
        '    iget-object v0, v5, LX/1JQ;->A0B:LX/08a;\n\n'
        '    .line 1117\n'
        '    invoke-static {v0}, LX/08a;->A00(LX/08a;)J\n\n'
        '    .line 1118\n'
        '    move-result-wide v0\n\n'
        '    iget-object v11, v5, LX/1JQ;->A0A:LX/1JP;\n\n'
        '    invoke-virtual {v11, v13, v0, v1}, LX/1JP;->A01(LX/0D4;J)Ljava/lang/Long;\n\n'
        '    const-string v11, "ConversationPinHelper/pinSelectedJids jid="\n\n'
        '    iget-object v2, v5, LX/1JQ;->A0D:LX/0KO;\n\n'
        '    invoke-interface {v8}, Ljava/util/List;->size()I\n\n'
        '    move-result v1\n\n'
        '    const v0, 0x7f1235f4\n\n'
        '    .line 1180\n'
        '    if-ne v1, v7, :cond_28\n\n'
        '    const v0, 0x7f1235f5\n\n'
        '    :cond_28\n'
        '    invoke-virtual {v2, v0, v3}, LX/0KO;->A0B(II)V\n\n')

OTHER = '    const-string v0, "SomethingElse/run"\n\n'

# Case 9 is the gate; 7 and 8 share a block, as they do in the real table.
GATE_TABLE = [':pswitch_9', ':pswitch_b', ':pswitch_c', ':pswitch_8', ':pswitch_7', ':pswitch_6',
              ':pswitch_5', ':pswitch_0', ':pswitch_0', ':pswitch_4', ':pswitch_3', ':pswitch_2',
              ':pswitch_1', ':pswitch_a']


def gate_class(block=GATE, table=None):
    labels = table or GATE_TABLE
    cases = {label: OTHER for label in dict.fromkeys(labels)}
    cases[':pswitch_4'] = block
    return lambda_class('X/3Q7', cases, labels)


def test_gate_resolves_the_case_and_everything_after_the_comparison():
    artifacts = feed(make(), gate_class())
    assert artifacts == {
        'PIN_GATE_CLASS_NAME': 'X.3Q7',
        'PIN_GATE_METHOD_NAME': 'run',
        'PIN_GATE_METHOD_SIG': '()V',
        'PIN_GATE_CASE_FIELD': '$t',
        'PIN_GATE_CASE': '9',
        'PIN_GATE_HELPER_FIELD': 'A00',
        'PIN_GATE_SELECTION_FIELD': 'A01',
        'PIN_GATE_DONE_FIELD': 'A04',
        'PIN_HELPER_CLASS_NAME': 'X.1JQ',
        'PIN_HELPER_STORE_FIELD': 'A02',
        'PIN_HELPER_PINNER_FIELD': 'A0A',
        'PIN_HELPER_CLOCK_FIELD': 'A0B',
        'PIN_HELPER_TOAST_FIELD': 'A0D',
        'PIN_STORE_GETTER_CLASS_NAME': 'X.20o',
        'PIN_STORE_GETTER_METHOD_NAME': 'A0m',
        'PIN_STORE_GETTER_METHOD_SIG': '(LX/05W;)LX/0Xg;',
        'PIN_PINNED_METHOD_NAME': 'A09',
        'PIN_PINNED_METHOD_SIG': '(LX/0Xg;Z)Ljava/util/Map;',
        'PIN_PINNER_METHOD_NAME': 'A01',
        'PIN_PINNER_METHOD_SIG': '(LX/0D4;J)Ljava/lang/Long;',
        'PIN_CLOCK_METHOD_NAME': 'A00',
        'PIN_CLOCK_METHOD_SIG': '(LX/08a;)J',
        'PIN_TOAST_METHOD_NAME': 'A0B',
        'PIN_TOAST_ONE': '0x7f1235f5',
        'PIN_TOAST_MANY': '0x7f1235f4',
    }


def test_gate_case_follows_the_table_not_the_label_name():
    # 2.26.39.79 put the same block at case 8.
    table = GATE_TABLE[:7] + [':pswitch_0', ':pswitch_4', ':pswitch_0'] + GATE_TABLE[10:]
    artifacts = feed(make(), gate_class(table=table))
    assert artifacts['PIN_GATE_CASE'] == '8'


def test_gate_does_not_fire_under_a_label_two_cases_share():
    table = GATE_TABLE[:8] + [':pswitch_4', ':pswitch_4'] + GATE_TABLE[10:]
    found = make()
    assert feed(found, gate_class(table=table)) == {}
    assert found.is_found is False


def test_gate_does_not_fire_on_a_second_pin_shaped_call():
    block = GATE + '    invoke-virtual {v11, v13, v0, v1}, LX/1JP;->A02(LX/0D4;J)Ljava/lang/Long;\n'
    found = make()
    assert feed(found, gate_class(block)) == {}
    assert found.is_found is False


def test_gate_does_not_guess_which_toast_is_which():
    # With the branch the other way round the first id would be the singular.
    block = GATE.replace('if-ne v1, v7, :cond_28', 'if-eq v1, v7, :cond_28')
    found = make()
    assert feed(found, gate_class(block)) == {}
    assert found.is_found is False


def test_gate_does_not_take_a_clock_read_far_from_the_pin():
    block = GATE.replace('    move-result-wide v0\n\n',
                         '    move-result-wide v0\n\n    const/4 v2, 0x0\n\n    const/4 v3, 0x0\n\n')
    found = make()
    assert feed(found, gate_class(block)) == {}
    assert found.is_found is False


def test_gate_does_not_fire_when_the_log_lines_are_in_different_cases():
    jid = '    const-string v11, "ConversationPinHelper/pinSelectedJids jid="\n\n'
    cases = {label: OTHER for label in dict.fromkeys(GATE_TABLE)}
    cases[':pswitch_4'] = GATE.replace(jid, '')
    cases[':pswitch_3'] = jid
    found = make()
    assert feed(found, lambda_class('X/3Q7', cases, GATE_TABLE)) == {}
    assert found.is_found is False


TRIM = ('    iget-object v7, v1, LX/3Q0;->A00:Ljava/lang/Object;\n\n'
        '    check-cast v7, LX/1JQ;\n\n'
        '    .line 412\n'
        '    const-string v0, "ConversationPinHelper/trimExcessPinnedChats failed to unpin jid="\n\n'
        '    const-string v0, "ConversationPinHelper/trimExcessPinnedChats unpinned="\n\n'
        '    const-string v0, "ConversationPinHelper/trimExcessPinnedChats no trimming needed pinned="\n\n')

TRIM_KEYS = {
    'PIN_TRIM_CLASS_NAME': 'X.3Q0',
    'PIN_TRIM_METHOD_NAME': 'run',
    'PIN_TRIM_METHOD_SIG': '()V',
    'PIN_TRIM_CASE_FIELD': '$t',
    'PIN_TRIM_CASE': '2',
}

TRIM_TABLE = [':pswitch_0', ':pswitch_1', ':pswitch_2', ':pswitch_3']


def trim_class(cases=None):
    return lambda_class('X/3Q0', cases or {':pswitch_0': OTHER, ':pswitch_1': OTHER,
                                           ':pswitch_2': TRIM, ':pswitch_3': OTHER}, TRIM_TABLE)


def test_trim_resolves_its_case():
    assert feed(make(), trim_class()) == TRIM_KEYS


def test_trim_needs_all_of_itself_under_the_label_it_found():
    # Skipping a case that only holds part of the trim would skip whatever
    # else is in it.
    unpinned = '    const-string v0, "ConversationPinHelper/trimExcessPinnedChats unpinned="\n\n'
    found = make()
    cases = {':pswitch_0': OTHER, ':pswitch_1': unpinned,
             ':pswitch_2': TRIM.replace(unpinned, ''), ':pswitch_3': OTHER}
    assert feed(found, trim_class(cases)) == {}
    assert found.is_found is False


def test_trim_ignores_the_labels_of_a_switch_inside_a_case():
    nested = ('    packed-switch v2, :pswitch_data_1\n\n'
              '    :pswitch_9\n'
              '    const/4 v3, 0x1\n\n'
              + TRIM
              + '    :pswitch_data_1\n'
              '    .packed-switch 0x0\n'
              '        :pswitch_9\n'
              '    .end packed-switch\n\n')
    cases = {':pswitch_0': OTHER, ':pswitch_1': OTHER, ':pswitch_2': nested, ':pswitch_3': OTHER}
    assert feed(make(), trim_class(cases))['PIN_TRIM_CASE'] == '2'


def test_trim_does_not_fire_outside_a_switched_lambda():
    plain = ('.class public final LX/1JQ;\n.super Ljava/lang/Object;\n\n'
             '.method public final A03()V\n'
             '    .locals 4\n'
             + TRIM
             + '    return-void\n'
             '.end method\n')
    found = make()
    assert feed(found, plain) == {}
    assert found.is_found is False


BENEFIT_KEYS = {
    'PIN_BENEFIT_CLASS_NAME': 'X.0Ng',
    'PIN_BENEFIT_METHOD_NAME': 'A0S',
    'PIN_BENEFIT_METHOD_SIG': '(LX/16G;)Z',
}


def benefit_check(name, benefit='LX/16G;', owner='X/0Ng', modifiers='public'):
    return ('.method %s %s(%s)Z\n'
            '    .locals 4\n\n'
            '    iget-boolean v1, p1, %s->retainedUntilAppRestart:Z\n\n'
            '    iget-object v0, p0, L%s;->A0A:Ljava/util/concurrent/ConcurrentHashMap;\n\n'
            '    invoke-virtual {v0, p1}, Ljava/util/AbstractMap;->get(Ljava/lang/Object;)Ljava/lang/Object;\n\n'
            '    invoke-virtual {p0, p1}, L%s;->A0T(%s)Z\n\n'
            '    return v0\n'
            '.end method\n\n' % (modifiers, name, benefit, benefit, owner, owner, benefit))


def benefit_class(*methods):
    return ('.class public final LX/0Ng;\n'
            '.super Ljava/lang/Object;\n\n'
            '.method public A04(LX/16G;)V\n'
            '    .locals 2\n'
            '    const-string v0, "PromoEligibilityManager/refreshEligibility: promo eligibility disabled"\n'
            '    return-void\n'
            '.end method\n\n'
            # The sibling it defers to: same shape, no cache.
            '.method public A0T(LX/16G;)Z\n'
            '    .locals 2\n'
            '    invoke-virtual {p1}, Ljava/lang/Enum;->ordinal()I\n'
            '    return v0\n'
            '.end method\n\n'
            + ''.join(methods))


def test_benefit_resolves_the_cached_check():
    assert feed(make(), benefit_class(benefit_check('A0S'))) == BENEFIT_KEYS


def test_benefit_skips_private_and_static_copies():
    found = make()
    artifacts = feed(found, benefit_class(benefit_check('A01', modifiers='private final'),
                                          benefit_check('A02', modifiers='public static'),
                                          benefit_check('A0S')))
    assert artifacts['PIN_BENEFIT_METHOD_NAME'] == 'A0S'


def test_benefit_does_not_fire_on_two_candidates():
    found = make()
    assert feed(found, benefit_class(benefit_check('A0R'), benefit_check('A0S'))) == {}
    assert found.is_found is False


def test_fires_once_all_three_classes_are_seen():
    found = make()
    artifacts = feed(found, trim_class(), benefit_class(benefit_check('A0S')))
    assert found.is_found is False
    artifacts.update(feed(found, gate_class()))
    assert found.is_found is True
    assert {key: artifacts[key] for key in TRIM_KEYS} == TRIM_KEYS
    assert {key: artifacts[key] for key in BENEFIT_KEYS} == BENEFIT_KEYS
    assert artifacts['PIN_GATE_CASE'] == '9'


def test_a_part_that_does_not_match_keeps_the_finder_from_firing():
    # The other parts' keys are still written, so the gate's unsubstituted
    # placeholders name the part that broke.
    found = make()
    artifacts = feed(found, trim_class(), benefit_class(benefit_check('A0R'), benefit_check('A0S')),
                     gate_class())
    assert found.is_found is False
    assert 'PIN_BENEFIT_CLASS_NAME' not in artifacts
    assert artifacts['PIN_TRIM_CASE'] == '2' and artifacts['PIN_GATE_CASE'] == '9'


def test_a_part_once_found_is_not_looked_for_again():
    found = make()
    feed(found, gate_class())
    assert found.class_filter(gate_class()) is False
    assert found.class_filter(trim_class()) is True
