from argparse import Namespace

from artifactory_generator.group_info import GroupInfoFinder

NULL_GID = ('    const-string v0, "group_info/on_create: exiting due to null gid"\n'
            '    invoke-static {v0}, Lcom/whatsapp/infra/logging/Log;->e(Ljava/lang/String;)V\n')

ON_CREATE = ('.method public onCreate(Landroid/os/Bundle;)V\n'
             '    .locals 4\n'
             '    const-string v0, "GroupChatInfoActivity/refresh"\n'
             + NULL_GID
             + '    return-void\n'
             '.end method\n')

GROUP_INFO = ('.class public Lcom/whatsapp/chatinfo/group/GroupChatInfoActivity;\n'
              '.super LX/0Hs;\n\n' + ON_CREATE)

# A helper that logs under the activity's tag but is not an Activity: no
# onCreate(Bundle). Seven of these ship alongside the real one.
HELPER = ('.class public final LX/2Bq;\n.super Ljava/lang/Object;\n\n'
          '.method public final A00()V\n'
          '    .locals 1\n'
          '    const-string v0, "GroupChatInfoActivity/refresh"\n'
          + NULL_GID
          + '    return-void\n'
          '.end method\n')

# An unrelated activity, with an onCreate but none of the markers.
OTHER_ACTIVITY = ('.class public Lcom/whatsapp/chatinfo/ContactInfoActivity;\n'
                  '.super LX/0Hs;\n\n'
                  '.method public onCreate(Landroid/os/Bundle;)V\n'
                  '    .locals 1\n'
                  '    const-string v0, "ContactInfoActivity/onCreate"\n'
                  '    return-void\n'
                  '.end method\n')


def finder():
    return GroupInfoFinder(Namespace(apk_path='x.apk', temp_path='./temp'))


def feed(found, *classes):
    artifacts = {}
    for class_data in classes:
        if found.class_filter(class_data):
            found.extract_artifacts(artifacts, class_data)
    return artifacts


def test_fires_on_the_group_info_activity():
    found = finder()
    artifacts = feed(found, OTHER_ACTIVITY, GROUP_INFO, HELPER)
    assert found.is_found is True
    assert artifacts['GROUP_INFO_ACTIVITY_CLASS_NAME'] == \
        'com.whatsapp.chatinfo.group.GroupChatInfoActivity'


def test_ignores_a_helper_that_shares_the_log_tag():
    """The tag prefix alone is not enough: helpers log under it too. Only a
    class that also declares onCreate(Bundle) is the screen."""
    found = finder()
    artifacts = feed(found, HELPER)
    assert found.is_found is False
    assert artifacts == {}


def test_ignores_an_activity_without_the_marker():
    found = finder()
    artifacts = feed(found, OTHER_ACTIVITY)
    assert found.is_found is False
    assert artifacts == {}


def test_requires_the_tag_to_match_the_class_name():
    """The tag is derived from the name rather than compared to a fixed one, so
    a class logging under someone else's tag is not the screen."""
    borrowed = GROUP_INFO.replace('GroupChatInfoActivity/refresh',
                                  'CommunityInfoActivity/refresh')
    found = finder()
    artifacts = feed(found, borrowed)
    assert found.is_found is False
    assert artifacts == {}


def test_survives_a_renamed_and_moved_class():
    """The package has already moved once (com.whatsapp.groupinfo ->
    com.whatsapp.chatinfo.group), and the dex file it lands in moves between
    releases, so neither may be an input."""
    moved = (GROUP_INFO
             .replace('com/whatsapp/chatinfo/group/GroupChatInfoActivity',
                      'com/whatsapp/groupinfo/ui/GroupChatInfoScreen')
             .replace('GroupChatInfoActivity/refresh', 'GroupChatInfoScreen/refresh'))
    found = finder()
    artifacts = feed(found, moved)
    assert found.is_found is True
    assert artifacts['GROUP_INFO_ACTIVITY_CLASS_NAME'] == \
        'com.whatsapp.groupinfo.ui.GroupChatInfoScreen'


def test_reads_the_jumbo_string_variant():
    """Large string indices use const-string/jumbo; a regex that omits it
    misses on some releases."""
    jumbo = GROUP_INFO.replace('const-string v0,', 'const-string/jumbo v0,')
    found = finder()
    feed(found, jumbo)
    assert found.is_found is True
