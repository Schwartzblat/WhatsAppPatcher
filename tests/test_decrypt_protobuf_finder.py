from argparse import Namespace

from artifactory_generator.decrypt_protobuf_finder import DecryptProtobufFinder


def message_class(name='LX/Bfr;'):
    """The inbound message protobuf. Only the kept field name identifies it."""
    return ('.class public final %s\n'
            '.super Lcom/google/protobuf/GeneratedMessageLite;\n\n'
            '.field public newsletterFollowerInviteMessage_:LX/BdZ;\n'
            '.field public protocolMessage_:LX/Bfk;\n\n'
            '.method public static A01([B)%s\n'
            '    .locals 1\n'
            '    return-object p0\n'
            '.end method\n' % (name, name))


def builder(name='LX/CZ8;', message='LX/Bfr;', method='A00', params='LX/82B;'):
    return ('.class public final %s\n'
            '.super Ljava/lang/Object;\n\n'
            '.field public A0P:%s\n'
            '.field public final A0S:%s\n\n'
            '.method public constructor <init>(LX/1UM;%s%sJ)V\n'
            '    .locals 0\n'
            '    return-void\n'
            '.end method\n\n'
            '.method public final %s()%s\n'
            '    .locals 30\n'
            '    new-instance v0, %s\n'
            '    return-object v0\n'
            '.end method\n' % (name, message, message, message, message, method, params, params))


# Shares the builder's constructor shape -- two repeated parameters and a long
# -- but builds a JSON envelope. One of these turns up in every release.
JSON_ENVELOPE = ('.class public final LX/F9R;\n'
                 '.super Ljava/lang/Object;\n\n'
                 '.method public constructor <init>(LX/1UM;Ljava/lang/Integer;Ljava/lang/Integer;J)V\n'
                 '    .locals 0\n'
                 '    return-void\n'
                 '.end method\n\n'
                 '.method public final A00()Lorg/json/JSONObject;\n'
                 '    .locals 1\n'
                 '    new-instance v0, Lorg/json/JSONObject;\n'
                 '    return-object v0\n'
                 '.end method\n')


def finder():
    return DecryptProtobufFinder(Namespace(apk_path='x.apk', temp_path='./temp'))


def feed(found, *classes):
    artifacts = {}
    for class_data in classes:
        if found.class_filter(class_data):
            found.extract_artifacts(artifacts, class_data)
    return artifacts


def test_fires_on_the_params_builder():
    found = finder()
    artifacts = feed(found, message_class(), builder(), JSON_ENVELOPE)
    assert found.is_found is True
    assert artifacts['DECRYPT_PROTOBUF_CLASS_NAME'] == 'X.Bfr'
    assert artifacts['PARSE_PARAMS_BUILDER_CLASS_NAME'] == 'X.CZ8'
    assert artifacts['PARSE_PARAMS_BUILDER_METHOD_NAME'] == 'A00'
    assert artifacts['PARSE_PARAMS_BUILDER_METHOD_SIG'] == '()LX/82B;'


def test_fires_when_the_builder_is_scanned_before_the_message():
    """Files arrive in filesystem order, and X/CZ8 sorts before X/Bfr as often
    as not, so candidates are kept until the message class names one."""
    found = finder()
    artifacts = feed(found, JSON_ENVELOPE, builder(), message_class())
    assert found.is_found is True
    assert artifacts['PARSE_PARAMS_BUILDER_CLASS_NAME'] == 'X.CZ8'


def test_ignores_a_builder_of_something_else():
    """The constructor shape is shared; the repeated type is what settles it."""
    found = finder()
    artifacts = feed(found, message_class(), builder(message='LX/9Rx;'))
    assert found.is_found is False
    assert artifacts == {}


def test_does_not_fire_without_the_message_class():
    found = finder()
    artifacts = feed(found, builder(), JSON_ENVELOPE)
    assert found.is_found is False
    assert artifacts == {}


def test_does_not_fire_when_two_builders_carry_the_message():
    """Ambiguity means don't fire: neither one is known to be the receive path."""
    found = finder()
    artifacts = feed(found, builder(), builder(name='LX/CZ9;'), message_class())
    assert found.is_found is False
    assert artifacts == {}


def test_ignores_a_builder_with_a_second_no_arg_method():
    """Two candidates inside one class leaves no single build method to hook."""
    twin = builder() + builder().split('\n\n', 2)[2].replace('A00', 'A01')
    found = finder()
    artifacts = feed(found, message_class(), twin)
    assert found.is_found is False
    assert artifacts == {}


def test_survives_a_release_that_renames_everything():
    """2.26.33.76's names, which share no character with 2.26.36.72's."""
    found = finder()
    artifacts = feed(found,
                     message_class('LX/E7y;'),
                     builder(name='LX/F71;', message='LX/E7y;', params='LX/FPk;'))
    assert found.is_found is True
    assert artifacts['DECRYPT_PROTOBUF_CLASS_NAME'] == 'X.E7y'
    assert artifacts['PARSE_PARAMS_BUILDER_CLASS_NAME'] == 'X.F71'
    assert artifacts['PARSE_PARAMS_BUILDER_METHOD_SIG'] == '()LX/FPk;'


def test_reads_a_class_name_that_contains_an_l():
    """stitch's own CLASS_NAME_RE is greedy and reads LX/BLz; as z. The message
    class has no L in its name and never exposed it; the builder is whatever R8
    happened to name it, so the finder carries its own."""
    found = finder()
    artifacts = feed(found, message_class('LX/BLz;'), builder(message='LX/BLz;', name='LX/CLd;'))
    assert found.is_found is True
    assert artifacts['DECRYPT_PROTOBUF_CLASS_NAME'] == 'X.BLz'
    assert artifacts['PARSE_PARAMS_BUILDER_CLASS_NAME'] == 'X.CLd'
