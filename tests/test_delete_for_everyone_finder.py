from argparse import Namespace

from artifactory_generator.delete_for_everyone import DeleteForEveryoneFinder

DIALOG_SIG = ('(Landroid/content/Context;LX/Diu;LX/Div;LX/Djf;'
              'Ljava/lang/String;Ljava/util/Set;Z)LX/GDQ;')

# The age test as apktool writes it: the message's timestamp, the window added
# to it, and the sum compared with the server-corrected clock. The .line
# markers between the instructions are what the real output looks like, and how
# many of them there are varies from build to build.
WINDOW = ('    iget-wide v0, v5, LX/1Ex;->A0F:J\n'
          '\n'
          '    .line 750\n'
          '    .line 751\n'
          '    const-wide/32 v10, 0xcdfe600\n'
          '\n'
          '    .line 752\n'
          '    add-long/2addr v0, v10\n'
          '\n'
          '    cmp-long v10, v0, v16\n')


def builder(name='A02', sig=DIALOG_SIG, modifiers='public ', body=WINDOW):
    return ('.method %s%s%s\n'
            '    .locals 20\n'
            '%s'
            '    return-object v2\n'
            '.end method\n' % (modifiers, name, sig, body))


def dialog_class(methods=None):
    """The class that builds the "Delete message?" dialog."""
    return ('.class public LX/CpB;\n'
            '.super Ljava/lang/Object;\n\n'
            + (builder() if methods is None else methods)
            + '.method public A03(Landroid/content/Context;LX/0Cw;Ljava/util/Collection;)'
            'Ljava/lang/String;\n'
            '    .locals 2\n'
            '    const-string v0, "dialog/delete no messages"\n'
            '    return-object v0\n'
            '.end method\n')


# One of the five other classes per release that log the same line: they open
# the dialog, they do not build it.
CALLER = ('.class public Lcom/whatsapp/gallery/ui/MediaGalleryActivity;\n'
          '.super Lcom/whatsapp/ui/coreui/WaActivity;\n\n'
          '.method public onCreateDialog(I)Landroid/app/Dialog;\n'
          '    .locals 3\n'
          '    const-string v0, "dialog/delete no messages"\n'
          '    invoke-static {v0}, Lcom/whatsapp/infra/logging/Log;->e(Ljava/lang/String;)V\n'
          '    return-object v1\n'
          '.end method\n')


def finder():
    return DeleteForEveryoneFinder(Namespace(apk_path='x.apk', temp_path='./temp'))


def feed(found, *classes):
    artifacts = {}
    for class_data in classes:
        if found.class_filter(class_data):
            found.extract_artifacts(artifacts, class_data)
    return artifacts


def test_resolves_the_builder_and_the_timestamp():
    found = finder()
    artifacts = feed(found, CALLER, dialog_class())
    assert found.is_found is True
    assert artifacts == {
        'REVOKE_DIALOG_CLASS_NAME': 'X.CpB',
        'REVOKE_DIALOG_METHOD_NAME': 'A02',
        'REVOKE_DIALOG_METHOD_SIG': DIALOG_SIG,
        'MESSAGE_TIMESTAMP_CLASS_NAME': 'X.1Ex',
        'MESSAGE_TIMESTAMP_FIELD_NAME': 'A0F',
    }


def test_ignores_a_caller_that_logs_the_same_line():
    """Six classes hold the anchor; only one of them does the comparison."""
    found = finder()
    artifacts = feed(found, CALLER)
    assert found.is_found is False
    assert artifacts == {}


def test_reads_the_window_without_depending_on_its_value():
    """A release that moves the two-and-a-half days must still resolve."""
    found = finder()
    widened = WINDOW.replace('0xcdfe600', '0x19bfcc00')
    artifacts = feed(found, dialog_class(builder(body=widened)))
    assert found.is_found is True
    assert artifacts['MESSAGE_TIMESTAMP_FIELD_NAME'] == 'A0F'


def test_reads_a_renamed_field_on_a_renamed_message_class():
    found = finder()
    renamed = WINDOW.replace('LX/1Ex;->A0F:J', 'LX/1G7;->A0B:J')
    artifacts = feed(found, dialog_class(builder(body=renamed)))
    assert found.is_found is True
    assert artifacts['MESSAGE_TIMESTAMP_CLASS_NAME'] == 'X.1G7'
    assert artifacts['MESSAGE_TIMESTAMP_FIELD_NAME'] == 'A0B'


def test_ignores_a_static_method_of_the_same_shape():
    """A static target cannot be hooked: the backup re-enters the replacement."""
    found = finder()
    artifacts = feed(found, dialog_class(builder(modifiers='public static ')))
    assert found.is_found is False
    assert artifacts == {}


def test_ignores_a_comparison_in_a_method_of_another_shape():
    """The selection and the revokable flag are what the replacement mirrors."""
    found = finder()
    other = '(Landroid/content/Context;Ljava/util/Set;)LX/GDQ;'
    artifacts = feed(found, dialog_class(builder(sig=other)))
    assert found.is_found is False
    assert artifacts == {}


def test_does_not_fire_on_two_candidate_builders():
    """Ambiguity means the wrong dialog could be the one that is hooked."""
    found = finder()
    artifacts = feed(found, dialog_class(builder() + builder(name='A04')))
    assert found.is_found is False
    assert artifacts == {}


def test_does_not_fire_when_the_comparison_is_gone():
    found = finder()
    artifacts = feed(found, dialog_class(builder(body='    const/4 v0, 0x1\n')))
    assert found.is_found is False
    assert artifacts == {}
