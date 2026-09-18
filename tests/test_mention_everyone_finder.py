from argparse import Namespace

from artifactory_generator.mention_everyone import MentionEveryoneFinder

# The shape of the real method on 2.26.36.71: the everyone marker is read as a
# self-typed singleton, while the span-kind constants read beside it are
# Integers -- which is what leaves exactly one candidate.
GET_MENTIONS = ('.method public getMentions()Ljava/util/List;\n'
                '    .locals 8\n'
                '\n'
                '    .line 0\n'
                '    invoke-virtual {p0}, Landroid/widget/EditText;->getText()Landroid/text/Editable;\n'
                '    move-result-object v2\n'
                '    iget-object v7, v6, LX/E2x;->A02:Ljava/lang/Integer;\n'
                '    sget-object v0, LX/02S;->A0C:Ljava/lang/Integer;\n'
                '    if-ne v7, v0, :cond_2\n'
                '    sget-object v0, LX/DIw;->A00:LX/DIw;\n'
                '    :goto_1\n'
                '    invoke-virtual {v5, v0}, Ljava/util/AbstractCollection;->add(Ljava/lang/Object;)Z\n'
                '    new-instance v0, LX/Dbi;\n'
                '    invoke-direct {v0, v7, v6}, LX/Dbi;-><init>(LX/0Cw;Ljava/lang/String;)V\n'
                '    return-object v0\n'
                '.end method\n')


def entry(class_name='com/whatsapp/mentions/ui/MentionableEntry', body=GET_MENTIONS):
    return ('.class public L%s;\n'
            '.super Lcom/whatsapp/conversation/platform/api/composer/entry/ConversationTextEntry;\n'
            '.source ""\n\n'
            '.method public getStringText()Ljava/lang/String;\n'
            '    .locals 1\n'
            '    return-object v0\n'
            '.end method\n\n'
            '%s' % (class_name, body))


# The caption composer declares getMentions() too, and delegates to the widget.
CAPTION_VIEW = ('.class public final Lcom/whatsapp/mediacomposer/ui/caption/CaptionView;\n'
                '.super Landroid/widget/LinearLayout;\n\n'
                '.method public getMentions()Ljava/util/List;\n'
                '    .locals 1\n'
                '    sget-object v0, LX/DIw;->A00:LX/DIw;\n'
                '    return-object v0\n'
                '.end method\n')


def finder():
    return MentionEveryoneFinder(Namespace(apk_path='x.apk', temp_path='./temp'))


def feed(found, *classes):
    artifacts = {}
    for class_data in classes:
        if found.class_filter(class_data):
            found.extract_artifacts(artifacts, class_data)
    return artifacts


def test_fires_on_the_composer_widget():
    found = finder()
    artifacts = feed(found, CAPTION_VIEW, entry())
    assert found.is_found is True
    assert artifacts['MENTION_ENTRY_CLASS_NAME'] == 'com.whatsapp.mentions.ui.MentionableEntry'
    assert artifacts['MENTION_GET_METHOD_NAME'] == 'getMentions'
    assert artifacts['MENTION_GET_METHOD_SIG'] == '()Ljava/util/List;'
    assert artifacts['MENTION_EVERYONE_CLASS_NAME'] == 'X.DIw'
    assert artifacts['MENTION_EVERYONE_FIELD_NAME'] == 'A00'


def test_fires_when_the_widget_has_not_moved_into_the_ui_package():
    """The class kept its name across every build examined; its package did not."""
    found = finder()
    artifacts = feed(found, entry(class_name='com/whatsapp/mentions/MentionableEntry'))
    assert found.is_found is True
    assert artifacts['MENTION_ENTRY_CLASS_NAME'] == 'com.whatsapp.mentions.MentionableEntry'


def test_ignores_a_class_that_only_declares_the_same_method():
    """CaptionView delegates to the widget; hooking it would miss the chat composer."""
    found = finder()
    artifacts = feed(found, CAPTION_VIEW)
    assert found.is_found is False
    assert artifacts == {}


def test_does_not_fire_on_two_candidate_markers():
    """Ambiguity means the wrong object could be added to a sent message."""
    found = finder()
    second = GET_MENTIONS.replace('    return-object v0\n',
                                  '    sget-object v1, LX/Dbj;->A00:LX/Dbj;\n'
                                  '    return-object v0\n')
    artifacts = feed(found, entry(body=second))
    assert found.is_found is False
    assert artifacts == {}


def test_does_not_fire_when_the_marker_is_gone():
    found = finder()
    without = GET_MENTIONS.replace('    sget-object v0, LX/DIw;->A00:LX/DIw;\n', '')
    artifacts = feed(found, entry(body=without))
    assert found.is_found is False
    assert artifacts == {}


def test_does_not_fire_on_two_candidate_methods():
    found = finder()
    artifacts = feed(found, entry(body=GET_MENTIONS + GET_MENTIONS.replace('getMentions', 'A0K')))
    assert found.is_found is False
    assert artifacts == {}


def test_ignores_a_static_method_of_the_same_shape():
    """A static target cannot be hooked: the backup re-enters the replacement."""
    found = finder()
    static = GET_MENTIONS.replace('.method public getMentions',
                                  '.method public static getMentions')
    artifacts = feed(found, entry(body=static))
    assert found.is_found is False
    assert artifacts == {}
