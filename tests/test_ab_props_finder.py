from argparse import Namespace

from artifactory_generator.ab_props import AbPropsFinder

ANCHOR = ('    const-string v0, "Unknown IntField: "\n'
          '    invoke-direct {v1, v0}, Ljava/lang/IllegalArgumentException;-><init>(Ljava/lang/String;)V\n')

CLS = 'LX/00E;'
CTX = 'LX/00G;'
FUNNEL_PARAMS = 'LX/00G;Ljava/lang/Integer;LX/09o;LX/09V;LX/09W;I'


def getter(name, ret, helper, box, modifiers='public '):
    """An accessor as R8 leaves it: a hand-off to a typed static helper."""
    return ('.method %s%s(I)%s\n'
            '    .locals 2\n'
            '    sget-object v1, %s->A03:%s\n'
            '    const/4 v0, 0x0\n'
            '    invoke-static {v1, p0, v0, p1}, %s->%s(%s%s%sI)%s\n'
            '    move-result v0\n'
            '    return v0\n'
            '.end method\n' % (modifiers, name, ret, CTX, CTX, CLS, helper, CTX, CLS, box, ret))


def helper(name, ret, box, funnel='A07', params=FUNNEL_PARAMS):
    """The typed static helper: the one place that calls into the funnel."""
    return ('.method public static final %s(%s%s%sI)%s\n'
            '    .locals 7\n'
            '    sget-object v3, LX/02S;->A0N:Ljava/lang/Integer;\n'
            '    iget-object v5, p1, %s->A0R:LX/09V;\n'
            '    move-object v2, p0\n'
            '    move p0, p3\n'
            '    invoke-direct/range {v1 .. v7}, %s->%s(%s)Ljava/lang/Object;\n'
            '    move-result-object v0\n'
            '    check-cast v0, Ljava/lang/Boolean;\n'
            '    return v0\n'
            '.end method\n' % (name, CTX, CLS, box, ret, CLS, CLS, funnel, params))


def funnel(name='A07', params=FUNNEL_PARAMS, instructions=120, modifiers='private final '):
    body = ''.join('    invoke-virtual {p0}, %s->A0q()V\n' % CLS for _ in range(instructions - 1))
    return ('.method %s%s(%s)Ljava/lang/Object;\n'
            '    .locals 17\n'
            '    .line 0\n'
            '    :cond_0\n'
            '%s'
            '    return-object v0\n'
            '.end method\n' % (modifiers, name, params, body))


# The accessors and their helpers in the shapes 2.26.36.71 has them. Every name
# here is one the finder has to discover.
ACCESSORS = (getter('A0y', 'Z', 'A0E', 'Ljava/lang/Boolean;')
             + getter('A0a', 'I', 'A02', 'Ljava/lang/Integer;')
             + getter('A0Y', 'F', 'A00', 'Ljava/lang/Float;')
             + getter('A0h', 'Ljava/lang/String;', 'A08', 'Ljava/lang/String;')
             + getter('A0l', 'Lorg/json/JSONObject;', 'A09', 'Ljava/lang/String;'))

HELPERS = (helper('A0E', 'Z', 'Ljava/lang/Boolean;')
           + helper('A02', 'I', 'Ljava/lang/Integer;')
           + helper('A00', 'F', 'Ljava/lang/Float;')
           + helper('A08', 'Ljava/lang/String;', 'Ljava/lang/String;')
           + helper('A09', 'Lorg/json/JSONObject;', 'Ljava/lang/String;'))

# Company the real class keeps: the same types behind a typed key object rather
# than a number, the abstract default tables, the per-id cache, and a static
# helper that never reaches the funnel.
NEIGHBOURS = ('.method public A11(LX/09R;)Z\n    .locals 1\n    return v0\n.end method\n'
              '.method public A0d(LX/09T;)I\n    .locals 1\n    return v0\n.end method\n'
              '.method public abstract A13()Lcom/google/common/collect/ImmutableMap;\n'
              '.end method\n'
              '.method public A0g(LX/00G;I)Ljava/lang/Object;\n    .locals 1\n    return-object v0\n'
              '.end method\n'
              '.method public static A0F(LX/00E;I)Z\n    .locals 1\n    return v0\n.end method\n')

HEADER = ('.class public abstract LX/00E;\n'
          '.super Ljava/lang/Object;\n\n'
          '.method public A01(LX/00G;LX/00E;Ljava/lang/Integer;I)I\n'
          '    .locals 4\n'
          + ANCHOR
          + '    throw v1\n'
          '.end method\n')

PROPS = HEADER + ACCESSORS + HELPERS + funnel() + NEIGHBOURS


def find(*classes):
    finder = AbPropsFinder(Namespace())
    artifacts = {}
    for class_data in classes:
        if finder.class_filter(class_data):
            finder.extract_artifacts(artifacts, class_data)
    return finder, artifacts


def test_resolves_the_funnel_every_accessor_ends_in():
    finder, artifacts = find(PROPS)
    assert finder.is_found
    assert artifacts == {
        'AB_PROPS_CLASS_NAME': 'X.00E',
        'AB_PROPS_BOOL_METHOD_NAME': 'A0y',
        'AB_PROPS_FUNNEL_METHOD_NAME': 'A07',
        'AB_PROPS_FUNNEL_METHOD_SIG': '(%s)Ljava/lang/Object;' % FUNNEL_PARAMS,
    }


def test_names_and_parameters_moved_between_releases_are_still_resolved():
    # 2.26.17.72: names that share not one letter with 2.26.36.71's, and a
    # funnel taking one reference fewer before the id.
    old_params = 'LX/00F;Ljava/lang/Integer;LX/096;LX/08X;I'
    renamed = PROPS.replace(FUNNEL_PARAMS, old_params).replace('->A07(', '->A05(')
    renamed = renamed.replace(funnel(params=old_params), funnel('A05', old_params))
    renamed = renamed.replace('A0y(I)Z', 'A0n(I)Z').replace('LX/00E;', 'LX/00D;')
    finder, artifacts = find(renamed)
    assert finder.is_found
    assert artifacts['AB_PROPS_CLASS_NAME'] == 'X.00D'
    assert artifacts['AB_PROPS_BOOL_METHOD_NAME'] == 'A0n'
    assert artifacts['AB_PROPS_FUNNEL_METHOD_NAME'] == 'A05'
    assert artifacts['AB_PROPS_FUNNEL_METHOD_SIG'] == '(%s)Ljava/lang/Object;' % old_params


def test_follows_an_extra_hop_on_the_way():
    # 2.26.33.76's int accessor went through an instance helper first.
    hop = ('.method private final A0V2(LX/00G;Ljava/lang/Integer;I)I\n'
           '    .locals 7\n'
           '    invoke-direct/range {v1 .. v7}, LX/00E;->A07(%s)Ljava/lang/Object;\n'
           '    return v0\n'
           '.end method\n' % FUNNEL_PARAMS)
    int_getter = getter('A0a', 'I', 'A02', 'Ljava/lang/Integer;')
    via_hop = int_getter.replace(
        'invoke-static {v1, p0, v0, p1}, LX/00E;->A02(LX/00G;LX/00E;Ljava/lang/Integer;I)I',
        'invoke-direct {p0, v1, v0, p1}, LX/00E;->A0V2(LX/00G;Ljava/lang/Integer;I)I')
    finder, artifacts = find(PROPS.replace(int_getter, via_hop) + hop)
    assert finder.is_found
    assert artifacts['AB_PROPS_FUNNEL_METHOD_NAME'] == 'A07'


def test_reads_a_class_name_with_an_l_in_it():
    finder, artifacts = find(PROPS.replace('LX/00E;', 'LX/0Lz;'))
    assert finder.is_found
    assert artifacts['AB_PROPS_CLASS_NAME'] == 'X.0Lz'


def test_ignores_classes_without_the_anchor():
    finder, artifacts = find(PROPS.replace('Unknown IntField: ', 'Unknown thing: '))
    assert not finder.is_found
    assert artifacts == {}


def test_does_not_fire_when_an_accessor_is_missing():
    # A release that dropped the float accessor is one this hook does not
    # understand; every read the funnel sees would still be covered, but a
    # class that has changed shape is not one to guess about.
    finder, artifacts = find(PROPS.replace(getter('A0Y', 'F', 'A00', 'Ljava/lang/Float;'), ''))
    assert not finder.is_found
    assert artifacts == {}


def test_does_not_fire_when_a_shape_is_ambiguous():
    finder, artifacts = find(PROPS + getter('A0z', 'Z', 'A0E', 'Ljava/lang/Boolean;'))
    assert not finder.is_found
    assert artifacts == {}


def test_does_not_fire_when_the_accessors_part_ways():
    # One accessor ending somewhere else means there is no single place that
    # sees every read, and hooking either would leave the other's reads bare.
    split = PROPS.replace(helper('A09', 'Lorg/json/JSONObject;', 'Ljava/lang/String;'),
                          helper('A09', 'Lorg/json/JSONObject;', 'Ljava/lang/String;', funnel='A06'))
    finder, artifacts = find(split + funnel('A06'))
    assert not finder.is_found
    assert artifacts == {}


def test_does_not_fire_on_a_funnel_small_enough_to_inline():
    # The point of the funnel is that no compiler filter can copy it into a
    # call site; one inside ART's 32-unit budget is no better than the accessors.
    finder, artifacts = find(PROPS.replace(funnel(), funnel(instructions=20)))
    assert not finder.is_found
    assert artifacts == {}


def test_does_not_fire_on_a_static_funnel():
    # ArtHooks cannot hook a static target: its backup re-enters the replacement.
    finder, artifacts = find(PROPS.replace(funnel(), funnel(modifiers='private static final ')))
    assert not finder.is_found
    assert artifacts == {}


def test_ignores_a_static_accessor():
    # Every read site is invoke-virtual; a static method of this shape is not
    # the accessor, and without it the set is incomplete.
    bool_getter = getter('A0y', 'Z', 'A0E', 'Ljava/lang/Boolean;')
    static = getter('A0y', 'Z', 'A0E', 'Ljava/lang/Boolean;', 'public static ')
    finder, artifacts = find(PROPS.replace(bool_getter, static))
    assert not finder.is_found
    assert artifacts == {}
