import pathlib
import platform
import shutil
import subprocess
import os
import glob
import re
import termcolor
import zipfile
import shutil


class Extractor:
    def __init__(self, path: str, output_path: str, temp_path: str = "./extracted"):
        self.apk_path = path
        self.temp_path = temp_path
        self.output_path = output_path

    def extract_apk(self):
        if os.path.exists(self.temp_path):
            shutil.rmtree(self.temp_path)
        apktool_base_path = pathlib.Path(__file__).parent / "bin" / "apktool"
        apk_tool = apktool_base_path / "apktool_2.7.0.jar"
        subprocess.check_call(
            [
                "java",
                "-jar",
                str(apk_tool),
                "d",
                "--output",
                str(self.temp_path),
                str(self.apk_path),
            ],
            timeout=20 * 60,
        )

    def extract_dex(self):
        with zipfile.ZipFile(self.apk_path) as z:
            z.extract('classes.dex')

    def compile_smali(self):
        if os.path.exists(self.output_path):
            os.remove(self.output_path)
        apktool_base_path = pathlib.Path(__file__).parent / "bin" / "apktool"
        apk_tool = apktool_base_path / "apktool_2.7.0.jar"
        command = [
            "java",
            "-jar",
            apk_tool,
            "build",
            "--use-aapt2",
            self.temp_path,
            "--output",
            self.output_path,
        ]
        subprocess.check_call(command, timeout=20 * 60)

    def sign_apk(self):
        command = [
            "java",
            "-jar",
            "uber-apk-signer-1.2.1.jar",
            "--apks",
            self.output_path,
        ]
        subprocess.check_call(command, timeout=20 * 60)
        os.remove(self.output_path)
        os.rename(f'{self.output_path.removesuffix(".apk")}-aligned-debugSigned.apk', self.output_path)
