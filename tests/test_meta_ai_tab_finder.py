from argparse import Namespace

from artifactory_generator.meta_ai_tab import MetaAiTabFinder

# baksmali writes .line markers between the constant and the return it feeds.
AI_FRAGMENT = ('.class public abstract Lcom/whatsapp/aihub/metaai/product/ui/AiFragmentBase;\n'
               '.super Lcom/whatsapp/ui/coreui/fragments/WaFragment;\n\n'
               '.method public B4Q()I\n'
               '    .locals 1\n'
               '\n'
               '    .line 0\n'
               '    const/16 v0, 0x3e8\n'
               '\n'
               '    .line 1\n'
               '    return v0\n'
               '.end method\n')


def tab_entry(tab_id):
    return ('    const/16 v0, %s\n'
            '    invoke-static {v0}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;\n'
            '    move-result-object v0\n'
            '    invoke-virtual {v4, v0}, Ljava/util/AbstractCollection;->add(Ljava/lang/Object;)Z\n' % tab_id)


def tabs_method(name, *ids):
    return ('.method public final %s()Ljava/util/ArrayList;\n'
            '    .locals 5\n'
            '    new-instance v4, Ljava/util/ArrayList;\n'
            '    invoke-direct {v4}, Ljava/util/ArrayList;-><init>()V\n'
            '%s'
            '    return-object v4\n'
            '.end method\n' % (name, ''.join(tab_entry(i) for i in ids)))


# The home tab list: Meta AI, Chats, Updates, Communities.
TABS = ('.class public final LX/0K5;\n.super Ljava/lang/Object;\n\n'
        + tabs_method('A07', '0x3e8', '0xc8', '0x12c', '0x190'))

# Another list of boxed ints of the same shape, on an unrelated class.
OTHER_LIST = ('.class public final LX/0jr;\n.super Ljava/lang/Object;\n\n'
              + tabs_method('A0D', '0xc8', '0x12c'))


def finder():
    return MetaAiTabFinder(Namespace(apk_path='x.apk', temp_path='./temp'))


def feed(found, *classes):
    artifacts = {}
    for class_data in classes:
        if found.class_filter(class_data):
            found.extract_artifacts(artifacts, class_data)
    return artifacts


def test_fires_on_the_home_tab_list():
    found = finder()
    artifacts = feed(found, AI_FRAGMENT, TABS, OTHER_LIST)
    assert found.is_found is True
    assert artifacts['META_AI_TAB_ID'] == '1000'
    assert artifacts['META_AI_TABS_CLASS_NAME'] == 'X.0K5'
    assert artifacts['META_AI_TABS_METHOD_NAME'] == 'A07'
    assert artifacts['META_AI_TABS_METHOD_SIG'] == '()Ljava/util/ArrayList;'


def test_fires_when_the_tab_list_is_scanned_before_the_fragment():
    """Files arrive in filesystem order, so candidates are kept until the id is."""
    found = finder()
    artifacts = feed(found, OTHER_LIST, TABS, AI_FRAGMENT)
    assert found.is_found is True
    assert artifacts['META_AI_TABS_CLASS_NAME'] == 'X.0K5'


def test_ignores_a_list_that_does_not_hold_the_meta_ai_tab():
    found = finder()
    artifacts = feed(found, AI_FRAGMENT, OTHER_LIST)
    assert found.is_found is False
    assert artifacts == {}


def test_does_not_fire_without_the_ai_fragment():
    """Older builds have no Meta AI tab: nothing identifies which id it would be."""
    found = finder()
    artifacts = feed(found, TABS, OTHER_LIST)
    assert found.is_found is False
    assert artifacts == {}


def test_does_not_fire_when_two_lists_hold_the_tab_id():
    """Ambiguity is judged over the candidates in hand when the id resolves.

    The driver drops a finder the moment it fires and never offers it another
    file, so a twin that turns up afterwards cannot be weighed -- which is why
    the candidate shape is narrow enough that the whole app yields a handful."""
    twin = OTHER_LIST.replace('0xc8', '0x3e8')
    found = finder()
    artifacts = feed(found, TABS, twin, AI_FRAGMENT)
    assert found.is_found is False
    assert artifacts == {}


def test_does_not_fire_when_the_fragment_reports_two_ids():
    """A second ()I means the tab it belongs to is no longer the one answer."""
    ambiguous = AI_FRAGMENT + AI_FRAGMENT.split('\n\n', 1)[1].replace('B4Q', 'B4R')
    found = finder()
    artifacts = feed(found, ambiguous, TABS)
    assert found.is_found is False
    assert artifacts == {}


def test_ignores_a_method_that_only_mentions_the_id():
    """The id has to be returned, not merely loaded: a ()I that computes with it
    says nothing about which tab the fragment is."""
    mentions = AI_FRAGMENT.replace('    return v0\n',
                                   '    add-int/lit8 v0, v0, 0x1\n    return v0\n')
    found = finder()
    artifacts = feed(found, mentions, TABS)
    assert found.is_found is False
    assert artifacts == {}


def test_survives_a_renamed_class_and_a_moved_tab_id():
    moved_fragment = AI_FRAGMENT.replace('0x3e8', '0x44c')
    moved_tabs = TABS.replace('LX/0K5;', 'LX/0Jn;').replace('0x3e8', '0x44c').replace('A07', 'A09')
    found = finder()
    artifacts = feed(found, moved_fragment, moved_tabs)
    assert found.is_found is True
    assert artifacts['META_AI_TAB_ID'] == '1100'
    assert artifacts['META_AI_TABS_CLASS_NAME'] == 'X.0Jn'
    assert artifacts['META_AI_TABS_METHOD_NAME'] == 'A09'
