from argparse import Namespace

from artifactory_generator.message_search import MessageSearchFinder

FUNNEL = ('.method public final A0H(LX/1QS;LX/0wl;Ljava/lang/String;)Ljava/lang/String;\n'
          '    .locals 11\n'
          '    %s v0, "fts_namespace:"\n'
          '    invoke-virtual {v1, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)'
          'Ljava/lang/StringBuilder;\n'
          '    return-object v0\n'
          '.end method\n')

ENCODER = ('.method public A0I(LX/0Cw;)Ljava/lang/String;\n'
           '    .locals 4\n'
           '    invoke-virtual {v0, p1}, LX/0cE;->A07(Lcom/whatsapp/infra/core/jid/Jid;)J\n'
           '    move-result-wide v2\n'
           '    const-wide/16 v0, 0xa\n'
           '    add-long/2addr v2, v0\n'
           '    const/16 v0, 0x24\n'
           '    invoke-static {v2, v3, v0}, Ljava/lang/Long;->toString(JI)Ljava/lang/String;\n'
           '    move-result-object v0\n'
           '    return-object v0\n'
           '.end method\n')

# Same signature as the funnel and in the same class, but it builds no
# namespace terms -- so signature shape alone must not be enough to fire.
DECOY = ('.method public final A0Q(LX/1QS;LX/0wl;Ljava/lang/String;)Ljava/lang/String;\n'
         '    .locals 2\n'
         '    const-string v0, "content:"\n'
         '    return-object v0\n'
         '.end method\n')


def index(const='const-string', funnel=FUNNEL, encoder=ENCODER, decoy=DECOY):
    return ('.class public abstract LX/17R;\n'
            '.super Ljava/lang/Object;\n\n'
            + (funnel % const) + encoder + decoy)


def run(class_data):
    finder = MessageSearchFinder(Namespace())
    artifacts = {}
    if finder.class_filter(class_data):
        finder.extract_artifacts(artifacts, class_data)
    return finder, artifacts


def test_resolves_the_funnel_and_the_encoding():
    finder, artifacts = run(index())
    assert finder.is_found
    assert artifacts['MESSAGE_SEARCH_CLASS_NAME'] == 'X.17R'
    assert artifacts['MESSAGE_SEARCH_METHOD_NAME'] == 'A0H'
    assert artifacts['MESSAGE_SEARCH_METHOD_SIG'] == '(LX/1QS;LX/0wl;Ljava/lang/String;)Ljava/lang/String;'
    assert artifacts['MESSAGE_SEARCH_TOKEN_OFFSET'] == '10'
    assert artifacts['MESSAGE_SEARCH_TOKEN_RADIX'] == '36'


def test_jumbo_string_constants_resolve_the_same():
    finder, artifacts = run(index(const='const-string/jumbo'))
    assert finder.is_found
    assert artifacts['MESSAGE_SEARCH_METHOD_NAME'] == 'A0H'


def test_zero_offset_builds_resolve():
    encoder = ENCODER.replace('    const-wide/16 v0, 0xa\n', '').replace('    add-long/2addr v2, v0\n', '')
    finder, artifacts = run(index(encoder=encoder))
    assert finder.is_found
    assert artifacts['MESSAGE_SEARCH_TOKEN_OFFSET'] == '0'


def test_two_funnel_candidates_do_not_fire():
    twice = index() + FUNNEL % 'const-string'
    finder, artifacts = run(twice)
    assert not finder.is_found
    assert artifacts == {}


def test_missing_encoder_does_not_fire():
    finder, artifacts = run(index(encoder=''))
    assert not finder.is_found
    assert artifacts == {}


def test_class_without_the_namespace_literal_is_skipped():
    finder, _ = run('.class public LX/9zz;\n' + ENCODER)
    assert not finder.is_found
