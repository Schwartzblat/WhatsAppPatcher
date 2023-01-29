import pathlib

from termcolor import cprint
import os
import re
import glob
from hashlib import md5
from typing import Callable
import importlib


exclude_imports = ['__init__.py', 'Patch.py', 'patcher.py']

class Patcher:

    BOOLEAN_TEST_METHOD_BODY_REGEX_SMALI = re.compile(
        "\.method public final \w+\(LX\/\w+;I\)Z.*?end method", re.DOTALL
    )
    RETURN_RE_SMALI = re.compile("[ ]*return v[0-9]")

    def __init__(self, extracted_path):
        self.extracted_path = extracted_path
        self.patches = []
        current_dir = pathlib.Path(os.path.split(__file__)[0]) / "patches"
        for path in glob.iglob(os.path.join(current_dir, "*.*")):
            if os.path.basename(path) in exclude_imports:
                continue
            module_name = "patches."+path.split('\\')[-1].split('.')[0]
            module = importlib.import_module(module_name, module_name)
            inner_class = getattr(module, dir(module)[0]) if dir(module)[0] != 'Patch' else getattr(module, dir(module)[1])
            self.patches.append(inner_class(self.extracted_path))

    def patch(self):
        for patch in self.patches:
            cprint(patch.print_message, 'green')
            self.patch_class(patch.class_filter, patch.class_modifier)


    def patch_class(self, class_filter: Callable, class_modifier: Callable) -> bool:
        class_path, class_data = self.find_class(class_filter)
        if class_path is None:
            return False
        new_class_data = class_modifier(class_data)
        with open(class_path, "w") as f:
            f.write(new_class_data)
        return True

    def find_class(self, class_filter: Callable):
        for filename in glob.iglob(
            os.path.join(self.extracted_path, "**", "*.smali"), recursive=True
        ):
            with open(filename, "r", encoding="utf8") as f:
                data = f.read()
                if class_filter(data):
                    return filename, data
        return None, None
