from argparse import Namespace

from artifactory_generator.row_binder import RowBinderFinder

GET_VIEW = ('.method public getView(ILandroid/view/View;'
            'Landroid/view/ViewGroup;)Landroid/view/View;\n'
            '    .registers 6\n'
            '    invoke-virtual {p0, p1}, LX/7uD;->A0B(I)LX/1If;\n'
            '    move-result-object v0\n'
            '    return-object p2\n'
            '.end method\n')

# The adapter's own log prefix, which WhatsApp kept when the adapter stopped
# being cursor-backed in 2.26.42; real builds carry it in the accessor itself.
ITEM = ('.method public A0B(I)LX/1If;\n'
        '    .registers 4\n'
        '    const-string v1, "ConversationCursorAdapter/getItem out-of-bounds "\n'
        '    iget-object v0, p0, LX/7uD;->A03:Landroid/database/Cursor;\n'
        '    return-object v0\n'
        '.end method\n')

LOG_ONLY = ('.method private A0D()V\n'
            '    .registers 2\n'
            '    const-string v0, "ConversationCursorAdapter/isSameSender: null senderJid in group chat, msg1="\n'
            '    return-void\n'
            '.end method\n')

# Adapter.getItem is generified, so the adapter always carries a bridge of the
# accessor's exact shape. Counting it would make the accessor look ambiguous.
GET_ITEM_BRIDGE = ('.method public bridge synthetic getItem(I)Ljava/lang/Object;\n'
                   '    .registers 3\n'
                   '    invoke-virtual {p0, p1}, LX/7uD;->A0B(I)LX/1If;\n'
                   '    move-result-object v0\n'
                   '    return-object v0\n'
                   '.end method\n')

# The conversation adapter: cursor-backed, binds through the framework getView.
ADAPTER = ('.class public final LX/7uD;\n.super Landroid/widget/CursorAdapter;\n\n'
           + ITEM + GET_ITEM_BRIDGE + GET_VIEW)

# 2.26.42.71's X/DS2: the same adapter, the same accessor and getView, now a
# plain BaseAdapter -- which is all the old anchor looked for.
BASE_ADAPTER = ADAPTER.replace('7uD', 'DS2').replace('A0B', 'A0H').replace('1If', '16o').replace(
    'Landroid/widget/CursorAdapter;', 'Landroid/widget/BaseAdapter;')

# The Starred/Kept messages adapter: a BaseAdapter whose row builder takes the
# FMessage directly. This is the shape the old finder hunted for, and it
# belongs to a screen this feature does not target.
B6J = ('.method public B6J(Landroid/view/View;Landroid/view/ViewGroup;LX/1If;I)'
       'Landroid/view/View;\n'
       '    .registers 6\n'
       '    return-object p1\n'
       '.end method\n')

STARRED_ADAPTER = ('.class public LX/5mI;\n.super Landroid/widget/BaseAdapter;\n\n'
                   + ('.method public getView(ILandroid/view/View;'
                      'Landroid/view/ViewGroup;)Landroid/view/View;\n'
                      '    .registers 6\n'
                      '    invoke-virtual {p0, p2, p3, v0, p1}, LX/5mI;->'
                      'B6J(Landroid/view/View;Landroid/view/ViewGroup;LX/1If;I)'
                      'Landroid/view/View;\n'
                      '    move-result-object v0\n'
                      '    return-object v0\n'
                      '.end method\n')
                   + '.method public AjC(I)LX/1If;\n    .registers 3\n'
                     '    return-object p0\n.end method\n'
                   + B6J)

# The delegating wrapper: same adapter surface, but every call goes out through
# an interface field. .super Object, so it is not a CursorAdapter either.
WRAPPER = ('.class public final LX/IH8;\n.super Ljava/lang/Object;\n\n'
           + ('.method public getView(ILandroid/view/View;'
              'Landroid/view/ViewGroup;)Landroid/view/View;\n'
              '    .registers 6\n'
              '    iget-object v0, p0, LX/IH8;->A01:LX/3Wq;\n'
              '    invoke-interface {v0, p2, p3, v1, p1}, LX/3Wq;->'
              'B6J(Landroid/view/View;Landroid/view/ViewGroup;LX/1If;I)'
              'Landroid/view/View;\n'
              '    move-result-object v0\n'
              '    return-object v0\n'
              '.end method\n')
           + '.method public AhR(I)LX/1If;\n    .registers 3\n'
             '    return-object p0\n.end method\n'
           + B6J)

# The audio picker's adapter: a CursorAdapter that binds the old way, through
# newView/bindView, and never declares getView.
BIND_VIEW_ADAPTER = ('.class public LX/3nK;\n.super Landroid/widget/CursorAdapter;\n\n'
                     '.method public bindView(Landroid/view/View;Landroid/content/Context;'
                     'Landroid/database/Cursor;)V\n    .registers 4\n    return-void\n'
                     '.end method\n'
                     '.method public newView(Landroid/content/Context;Landroid/database/Cursor;'
                     'Landroid/view/ViewGroup;)Landroid/view/View;\n    .registers 4\n'
                     '    return-object p1\n.end method\n')


def finder():
    return RowBinderFinder(Namespace(apk_path='x.apk', temp_path='./temp'))


def test_fires_on_the_conversation_cursor_adapter():
    artifacts = {}
    found = finder()
    assert found.class_filter(ADAPTER) is True
    found.extract_artifacts(artifacts, ADAPTER)
    assert found.is_found is True
    assert artifacts['ROW_BINDER_CLASS_NAME'] == 'X.7uD'
    assert artifacts['ROW_BINDER_METHOD_NAME'] == 'getView'
    assert artifacts['ROW_BINDER_METHOD_SIG'] == (
        '(ILandroid/view/View;Landroid/view/ViewGroup;)Landroid/view/View;')
    assert artifacts['ROW_ITEM_METHOD_NAME'] == 'A0B'
    assert artifacts['ROW_ITEM_METHOD_SIG'] == '(I)LX/1If;'


def test_does_not_fire_on_the_starred_messages_base_adapter():
    """The regression guard for the defect this rule fixes.

    X/5mI satisfied every rule the shape-and-reachability finder had -- a
    getView that self-invokes a (View, ViewGroup, FMessage, int) row builder --
    but it is the Starred/Kept messages adapter, not the conversation's. The
    hook installed on a screen nobody opened. It must not win."""
    artifacts = {}
    found = finder()
    assert found.class_filter(STARRED_ADAPTER) is False
    found.extract_artifacts(artifacts, STARRED_ADAPTER)
    assert found.is_found is False
    assert artifacts == {}


def test_does_not_fire_on_a_delegating_wrapper():
    artifacts = {}
    found = finder()
    assert found.class_filter(WRAPPER) is False
    found.extract_artifacts(artifacts, WRAPPER)
    assert found.is_found is False
    assert artifacts == {}


def test_ignores_a_cursor_adapter_without_get_view():
    assert finder().class_filter(BIND_VIEW_ADAPTER) is False


def test_does_not_fire_when_the_item_accessor_is_ambiguous():
    ambiguous = ADAPTER + ITEM.replace('A0B', 'A0C')
    artifacts = {}
    found = finder()
    assert found.class_filter(ambiguous) is True
    found.extract_artifacts(artifacts, ambiguous)
    assert found.is_found is False
    assert artifacts == {}


def test_does_not_fire_without_an_item_accessor():
    plain = ('.class public final LX/7uD;\n.super Landroid/widget/CursorAdapter;\n\n'
             + LOG_ONLY + GET_ITEM_BRIDGE + GET_VIEW)
    artifacts = {}
    found = finder()
    assert found.class_filter(plain) is True
    found.extract_artifacts(artifacts, plain)
    assert found.is_found is False
    assert artifacts == {}


def test_survives_a_renamed_class_method_and_message_type():
    moved = ADAPTER.replace('7uD', '7m3').replace('A0B', 'A09').replace('1If', '1G7')
    artifacts = {}
    found = finder()
    assert found.class_filter(moved) is True
    found.extract_artifacts(artifacts, moved)
    assert found.is_found is True
    assert artifacts['ROW_BINDER_CLASS_NAME'] == 'X.7m3'
    assert artifacts['ROW_BINDER_METHOD_NAME'] == 'getView'
    assert artifacts['ROW_ITEM_METHOD_NAME'] == 'A09'
    assert artifacts['ROW_ITEM_METHOD_SIG'] == '(I)LX/1G7;'


def test_tolerates_extra_method_modifiers():
    recompiled = (ADAPTER
                  .replace('.method public getView', '.method public final getView')
                  .replace('.method public A0B', '.method public final A0B'))
    artifacts = {}
    found = finder()
    assert found.class_filter(recompiled) is True
    found.extract_artifacts(artifacts, recompiled)
    assert found.is_found is True
    assert artifacts['ROW_BINDER_METHOD_NAME'] == 'getView'
    assert artifacts['ROW_ITEM_METHOD_NAME'] == 'A0B'


def test_fires_on_the_conversation_adapter_once_it_is_a_base_adapter():
    artifacts = {}
    found = finder()
    assert found.class_filter(BASE_ADAPTER) is True
    found.extract_artifacts(artifacts, BASE_ADAPTER)
    assert found.is_found is True
    assert artifacts['ROW_BINDER_CLASS_NAME'] == 'X.DS2'
    assert artifacts['ROW_BINDER_METHOD_NAME'] == 'getView'
    assert artifacts['ROW_ITEM_METHOD_NAME'] == 'A0H'
    assert artifacts['ROW_ITEM_METHOD_SIG'] == '(I)LX/16o;'


def test_the_log_prefix_may_sit_anywhere_in_the_class():
    elsewhere = BASE_ADAPTER.replace('    const-string v1, "ConversationCursorAdapter/getItem out-of-bounds "\n', '') + LOG_ONLY
    artifacts = {}
    found = finder()
    assert found.class_filter(elsewhere) is True
    found.extract_artifacts(artifacts, elsewhere)
    assert found.is_found is True


def test_an_adapter_without_the_conversation_log_prefix_does_not_fire():
    """Many of WhatsApp's BaseAdapters declare getView and one (I) accessor; the
    conversation's own log prefix is what picks the right one out of them. It is
    required of a CursorAdapter too, so one rule decides on every build."""
    anonymous = ADAPTER.replace('ConversationCursorAdapter', 'StickerPickerAdapter')
    artifacts = {}
    found = finder()
    assert found.class_filter(anonymous) is False
    found.extract_artifacts(artifacts, anonymous)
    assert found.is_found is False
    assert artifacts == {}
