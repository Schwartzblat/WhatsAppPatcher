from argparse import Namespace

from artifactory_generator.upgrade import UpgradeCheckFinder

TAG = 'WebsiteDistributionCheck'
SOURCE = 'upgrade source initialized; path='
SETTING = 'install_non_market_apps'


def tag_method(name='getTag', op='const-string'):
    return ('.method public %s()Ljava/lang/String;\n'
            '    .locals 1\n'
            '    %s v0, "%s"\n'
            '    return-object v0\n'
            '.end method\n' % (name, op, TAG))


def check_method(name='Bf0', op='const-string', anchors=(SOURCE, SETTING), filler=60, modifiers='public '):
    """The startup task's body: the upgrade URLs, the install setting, then the schedule."""
    body = ''.join('    %s v0, "%s"\n'
                   '    invoke-static {v0, v4}, LX/20r;->A1R(Ljava/lang/String;Ljava/lang/String;)V\n'
                   % (op, anchor) for anchor in anchors)
    body += ''.join('    .line %d\n    invoke-interface {v1}, LX/08z;->BQo()Z\n\n' % i for i in range(filler))
    return ('.method %s%s()V\n'
            '    .locals 6\n'
            '%s'
            '    return-void\n'
            '.end method\n' % (modifiers, name, body))


def empty_method(name='BdQ'):
    return '.method public %s()V\n    .locals 0\n    .line 0\n    return-void\n.end method\n' % name


def task(*methods, cls='LX/Fuz;'):
    return ('.class public %s\n.super Ljava/lang/Object;\n.source ""\n\n'
            '# interfaces\n.implements LX/0Aj;\n\n'
            '.field public final A00:LX/00t;\n\n' % cls
            + ''.join(methods))


def finder():
    return UpgradeCheckFinder(Namespace(apk_path='x.apk', temp_path='./temp'))


def feed(found, *classes):
    artifacts = {}
    for class_data in classes:
        if found.class_filter(class_data):
            found.extract_artifacts(artifacts, class_data)
    return artifacts


def test_fires_on_the_startup_task_that_schedules_the_download():
    found = finder()
    artifacts = feed(found, task(check_method(), empty_method('Bf1'), tag_method()))
    assert found.is_found is True
    assert artifacts == {'UPGRADE_CHECK_CLASS_NAME': 'X.Fuz',
                         'UPGRADE_CHECK_METHOD_NAME': 'Bf0',
                         'UPGRADE_CHECK_METHOD_SIG': '()V'}


def test_reads_jumbo_strings_and_a_renamed_tag_accessor():
    """2.26.37.74 and 2.26.29.74 load the anchor as const-string/jumbo, and
    2.26.29.74 names the tag accessor Az7 rather than getTag."""
    found = finder()
    artifacts = feed(found, task(tag_method('Az7', 'const-string/jumbo'),
                                 check_method('BSK', 'const-string/jumbo'), cls='LX/DGW;'))
    assert found.is_found is True
    assert artifacts['UPGRADE_CHECK_CLASS_NAME'] == 'X.DGW'
    assert artifacts['UPGRADE_CHECK_METHOD_NAME'] == 'BSK'


def test_declines_when_two_methods_hold_the_anchors():
    found = finder()
    assert feed(found, task(check_method('Bf0'), check_method('Bf1'), tag_method())) == {}
    assert found.is_found is False


def test_declines_on_the_source_string_without_the_install_setting():
    found = finder()
    assert feed(found, task(check_method(anchors=(SOURCE,)), tag_method())) == {}
    assert found.is_found is False


def test_declines_on_a_build_without_the_website_updater():
    """2.26.39.79: the task is still registered, its body is a bare return-void."""
    found = finder()
    assert feed(found, task(empty_method('BdQ'), empty_method('BdR'), tag_method())) == {}
    assert found.is_found is False


def test_declines_on_a_body_small_enough_to_inline():
    found = finder()
    assert feed(found, task(check_method(filler=10), tag_method())) == {}
    assert found.is_found is False


def test_declines_on_a_static_method():
    found = finder()
    assert feed(found, task(check_method(modifiers='public static '), tag_method())) == {}
    assert found.is_found is False


def test_ignores_the_anchors_outside_the_tagged_task():
    """The tag is what makes it the startup task; the strings alone are not."""
    found = finder()
    assert feed(found, task(check_method())) == {}
    assert found.is_found is False
