import re

#: The class a smali file declares, as its descriptor without the ``L`` and the
#: ``;`` -- ``X/1LK``. Use ``.search``: the ``.class`` line is the first line,
#: but ``re.M`` makes the anchor mean it rather than rely on it.
#:
#: Every finder here used to take stitch's ``CLASS_NAME_RE`` instead, which is
#: greedy: it backtracks to the *last* ``L`` on the line and reads ``LX/1LK;``
#: as ``K``. Nothing fails where it would be noticed -- the key is written, the
#: gate passes, the build is clean -- and the hook dies at runtime on
#: ``ClassNotFoundException: K``, which is how the Meta AI button gate went
#: quiet on 2.26.37.74. R8 hands out names with an ``L`` in them as readily as
#: any other, so every finder was one rename away from it. It also only matched
#: ``.class public``; a package-private class is still a class.
CLASS_RE = re.compile(r'^\.class[^\n]*?\sL(?P<name>[^;\s]+);', re.M)

#: apktool interleaves `.line` directives and blank lines between instructions,
#: and how many it emits varies from build to build.
GAP = r'(?:[ \t]*(?:\.line \d+)?\n)*'

#: A method as apktool writes it: modifiers, name, descriptor and body.
METHOD_RE = re.compile(
    r'^\.method (?P<modifiers>[\w ]*?)(?P<name>[\w$<>]+)(?P<sig>\([^)\n]*\)[\w/$;\[]+)\n'
    r'(?P<body>.*?)^\.end method$',
    re.MULTILINE | re.DOTALL)

# R8 merges a release's small lambdas into a handful of classes that each
# carry an int field -- `$t` -- and dispatch on it as the first thing `run()`
# or `invoke()` does.
_DISPATCH_RE = re.compile(
    r'iget (?P<reg>[vp]\d+), [vp]\d+, L[^;\s]+;->(?P<field>[\w$]+):I\n'
    + GAP
    + r'[ \t]*packed-switch (?P=reg), (?P<data>:pswitch_data_[0-9a-f]+)\n')

_LABEL = r':pswitch_[0-9a-f]+'


def lambda_case(body, position):
    """Which lambda of a merged class the code at ``position`` belongs to.

    ``body`` is the dispatching method's body and ``position`` an offset into
    it. Answers ``(field, case, block)`` -- the field the method switches on,
    the value of it that runs that code, and the code from that case's label
    to the next one -- or None when that cannot be said for certain.

    The case number is what a hook compares against, and it moves: the pin gate
    is case 9 of one class on 2.26.40.72 and case 8 of another on 2.26.39.79.
    Only the table says which, and only labels from *this* switch count: a case
    with a switch of its own carries `:pswitch_` labels of the same spelling.

    None when the code sits ahead of every case (the default branch) or under a
    label that several case numbers share. A hook that names the wrong case
    does the wrong lambda's work or skips it, in code that has nothing to do
    with the patch, and that is not worth a guess.
    """
    dispatch = _DISPATCH_RE.search(body)
    if dispatch is None or dispatch.start() > position:
        return None
    table = re.search(
        r'^[ \t]*' + re.escape(dispatch.group('data')) + r'\n'
        r'[ \t]*\.packed-switch (?P<base>0x[0-9a-f]+)\n'
        r'(?P<labels>(?:[ \t]*' + _LABEL + r'\n)+)'
        r'[ \t]*\.end packed-switch', body, re.M)
    if table is None:
        return None
    labels = table.group('labels').split()
    starts = {}
    for label in set(labels):
        found = re.search(r'^[ \t]*' + re.escape(label) + r'$', body, re.M)
        if found is None:
            return None
        starts[label] = found.start()
    owner = max((label for label in starts if starts[label] <= position),
                key=lambda label: starts[label], default=None)
    if owner is None:
        return None
    cases = [int(table.group('base'), 16) + index
             for index, label in enumerate(labels) if label == owner]
    if len(cases) != 1:
        return None
    end = min([start for start in starts.values() if start > starts[owner]]
              + [table.start()])
    if position >= end:
        return None
    return dispatch.group('field'), cases[0], body[starts[owner]:end]
