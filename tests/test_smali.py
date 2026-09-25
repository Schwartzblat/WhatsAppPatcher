from stitch.artifactory_generator.SimpleArtifactoryFinder import CLASS_NAME_RE

from artifactory_generator.smali import CLASS_RE


def name(class_data):
    match = CLASS_RE.search(class_data)
    return match.group('name') if match is not None else None


def test_reads_a_name_with_an_l_in_it():
    assert name('.class public final LX/1LK;\n.super Ljava/lang/Object;\n') == 'X/1LK'


def test_stitchs_own_regex_is_the_one_that_gets_it_wrong():
    # Kept as a tripwire: if stitch ever fixes this, the shared regex can go.
    match = CLASS_NAME_RE.match('.class public final LX/1LK;\n')
    assert match.group('name') == 'K'


def test_reads_every_modifier_combination():
    assert name('.class public abstract LX/00D;\n') == 'X/00D'
    assert name('.class final LX/BLz;\n') == 'X/BLz'
    assert name('.class LX/0Lz;\n') == 'X/0Lz'
    assert name('.class public interface abstract LX/09m;\n') == 'X/09m'


def test_reads_packages_and_inner_classes():
    assert name('.class public Lcom/whatsapp/mentions/ui/MentionableEntry;\n') == \
        'com/whatsapp/mentions/ui/MentionableEntry'
    assert name('.class public final LX/0Ck$A00;\n') == 'X/0Ck$A00'


def test_does_not_read_a_type_named_further_down():
    body = ('.class public LX/07t;\n.super LX/00D;\n\n'
            '.field public A00:LX/0Lz;\n')
    assert name(body) == 'X/07t'
