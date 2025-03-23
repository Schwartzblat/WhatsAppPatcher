import os
import pathlib
import subprocess
import typing

from ultimate_patcher import config


def extract_apk(apk_path: str, output_path: str = './extracted') -> None:
    if os.path.exists(output_path):
        return
    subprocess.check_call(
        [
            "java",
            "-jar",
            config.APKTOOL_PATH,
            "-q",
            "d",
            "-r",
            "--output",
            output_path,
            apk_path,
        ],
        timeout=20 * 60,
    )


def compile_apk(input_path: str = './extracted', output_path: str = 'output.apk') -> None:
    subprocess.check_call([
        "java",
        "-jar",
        config.APKTOOL_PATH,
        "-q",
        "build",
        "--use-aapt2",
        input_path,
        "--output",
        output_path
    ],
        timeout=20 * 60,

    )


def sign_apk(apk_path: str, output_path: str = 'signed-output.apk') -> None:
    subprocess.check_call(
        [
            "java",
            "-jar",
            config.UBER_APK_SIGNER_PATH,
            "--apks",
            apk_path
        ],
        timeout=20 * 60,
    )
    os.remove(apk_path)
    os.rename(
        f'{apk_path.removesuffix(".apk")}-aligned-debugSigned.apk',
        output_path,
    )


def _recursive_search_class(parent: pathlib.Path, class_path: list) -> typing.Optional[pathlib.Path]:
    for child in parent.iterdir():
        if len(class_path) == 1 and child.is_file() and child.name == f'{class_path[0]}.smali':
            return child
        elif child.is_dir() and child.name == class_path[0]:
            return _recursive_search_class(child, class_path[1:])
    return None


def find_smali_file_by_class_name(parent: pathlib.Path, class_name: str) -> typing.Optional[pathlib.Path]:
    for child in parent.iterdir():
        if not child.is_dir() or not str(child.name).startswith('smali'):
            continue
        file_path = _recursive_search_class(child, class_name.split('.'))
        if file_path:
            return file_path
    return None
