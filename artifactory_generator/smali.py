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
