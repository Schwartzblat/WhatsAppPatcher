from argparse import Namespace

from artifactory_generator.ab_props import AbPropsFinder

ANCHOR = ('    const-string v0, "Unknown IntField: "\n'
          '    invoke-direct {v1, v0}, Ljava/lang/IllegalArgumentException;-><init>(Ljava/lang/String;)V\n')


def getter(name, ret, modifiers='public '):
    return ('.method %s%s(I)%s\n'
            '    .locals 2\n'
            '    sget-object v1, LX/00G;->A03:LX/00G;\n'
            '    const/4 v0, 0x0\n'
            '    invoke-static {v1, p0, v0, p1}, LX/00E;->A0E(LX/00G;LX/00E;Ljava/lang/Boolean;I)Z\n'
            '    move-result v0\n'
            '    return v0\n'
            '.end method\n' % (modifiers, name, ret))


# The accessors the app reads properties through, in the shapes 2.26.36.71 has
# them. Every name here is one the finder has to discover.
ACCESSORS = (getter('A0y', 'Z')
             + getter('A0a', 'I')
             + getter('A0Y', 'F')
             + getter('A0h', 'Ljava/lang/String;')
             + getter('A0l', 'Lorg/json/JSONObject;'))

# Company the real class keeps: the same types behind a typed key object rather
# than a number, the abstract default tables, and a static helper.
NEIGHBOURS = ('.method public A11(LX/09R;)Z\n    .locals 1\n    return v0\n.end method\n'
              '.method public A0d(LX/09T;)I\n    .locals 1\n    return v0\n.end method\n'
              '.method public abstract A13()Lcom/google/common/collect/ImmutableMap;\n'
              '.end method\n'
              '.method public static A0F(LX/00E;I)Z\n    .locals 1\n    return v0\n.end method\n')

PROPS = ('.class public abstract LX/00E;\n'
         '.super Ljava/lang/Object;\n\n'
         '.method public A01(LX/00G;LX/00E;Ljava/lang/Integer;I)I\n'
         '    .locals 4\n'
         + ANCHOR
         + '    throw v1\n'
         '.end method\n'
         + ACCESSORS + NEIGHBOURS)


def find(*classes):
    finder = AbPropsFinder(Namespace())
    artifacts = {}
    for class_data in classes:
        if finder.class_filter(class_data):
            finder.extract_artifacts(artifacts, class_data)
    return finder, artifacts


def test_resolves_the_class_and_all_five_accessors():
    finder, artifacts = find(PROPS)
    assert finder.is_found
    assert artifacts['AB_PROPS_CLASS_NAME'] == 'X.00E'
    assert artifacts['AB_PROPS_BOOL_METHOD_NAME'] == 'A0y'
    assert artifacts['AB_PROPS_INT_METHOD_NAME'] == 'A0a'
    assert artifacts['AB_PROPS_FLOAT_METHOD_NAME'] == 'A0Y'
    assert artifacts['AB_PROPS_STRING_METHOD_NAME'] == 'A0h'
    assert artifacts['AB_PROPS_JSON_METHOD_NAME'] == 'A0l'
    assert artifacts['AB_PROPS_BOOL_METHOD_SIG'] == '(I)Z'
    assert artifacts['AB_PROPS_JSON_METHOD_SIG'] == '(I)Lorg/json/JSONObject;'


def test_names_moved_between_releases_are_still_resolved():
    # 2.26.17.72's names, which share not one letter with 2.26.36.71's.
    renamed = PROPS.replace('A0y', 'A0n').replace('A0a', 'A0T').replace('A0Y', 'A0R')
    renamed = renamed.replace('A0h', 'A0Y').replace('A0l', 'A0b').replace('LX/00E;', 'LX/00D;')
    finder, artifacts = find(renamed)
    assert finder.is_found
    assert artifacts['AB_PROPS_CLASS_NAME'] == 'X.00D'
    assert artifacts['AB_PROPS_BOOL_METHOD_NAME'] == 'A0n'
    assert artifacts['AB_PROPS_STRING_METHOD_NAME'] == 'A0Y'


def test_ignores_classes_without_the_anchor():
    finder, artifacts = find(PROPS.replace('Unknown IntField: ', 'Unknown thing: '))
    assert not finder.is_found
    assert artifacts == {}


def test_does_not_fire_when_an_accessor_is_missing():
    # A release that dropped the float accessor is one this hook does not
    # understand; half a set would leave those reads unreachable in silence.
    finder, artifacts = find(PROPS.replace(getter('A0Y', 'F'), ''))
    assert not finder.is_found
    assert artifacts == {}


def test_does_not_fire_when_a_shape_is_ambiguous():
    finder, artifacts = find(PROPS + getter('A0z', 'Z'))
    assert not finder.is_found
    assert artifacts == {}


def test_ignores_a_static_accessor():
    # ArtHooks cannot hook a static target: its backup re-enters the
    # replacement. R8 staticizes an instance method whose receiver goes unused,
    # so this is a shape a future release can actually produce.
    finder, artifacts = find(PROPS.replace(getter('A0y', 'Z'), getter('A0y', 'Z', 'public static ')))
    assert not finder.is_found
    assert artifacts == {}
