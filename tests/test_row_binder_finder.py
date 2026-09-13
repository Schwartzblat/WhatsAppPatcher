from argparse import Namespace

from artifactory_generator.row_binder import RowBinderFinder

GET_VIEW = ('.method public getView(ILandroid/view/View;'
            'Landroid/view/ViewGroup;)Landroid/view/View;\n'
            '    .registers 6\n'
            '    invoke-virtual {p0, p1}, LX/7uD;->A0B(I)LX/1If;\n'
            '    move-result-object v0\n'
            '    return-object p2\n'
            '.end method\n')

ITEM = ('.method public A0B(I)LX/1If;\n'
        '    .registers 4\n'
        '    iget-object v0, p0, LX/7uD;->A03:Landroid/database/Cursor;\n'
        '    return-object v0\n'
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
             + GET_ITEM_BRIDGE + GET_VIEW)
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
