import pathlib
import shutil
import subprocess
import os
from termcolor import cprint
import zipfile


class Extractor:
    def __init__(self, path: str, output_path: str, temp_path: str = "./extracted"):
        self.apk_path = path
        self.temp_path = temp_path
        self.output_path = output_path

    def extract_apk(self):
        if os.path.exists(self.temp_path):
            shutil.rmtree(self.temp_path)
        apktool_base_path = pathlib.Path(__file__).parent / "bin"
        apk_tool = apktool_base_path / "apktool_2.7.0.jar"
        cprint("[+] Running apktool to decompile the apk.", "green")
        subprocess.check_call(
            [
                "java",
                "-jar",
                str(apk_tool),
                "-q",
                "d",
                "--output",
                str(self.temp_path),
                str(self.apk_path),
            ],
            timeout=20 * 60,
        )
        cprint("[+] Apktool decompiled the apk.", "green")
        self.extract_dex()

    def extract_dex(self):
        with zipfile.ZipFile(self.apk_path) as z:
            z.extract("classes.dex", self.temp_path)

    def compile_smali(self):
        if os.path.exists(self.temp_path + "\\classes.dex"):
            os.remove(self.temp_path + "\\classes.dex")
        if os.path.exists(self.output_path):
            os.remove(self.output_path)
        apktool_base_path = pathlib.Path(__file__).parent / "bin"
        apk_tool = apktool_base_path / "apktool_2.7.0.jar"
        command = [
            "java",
            "-jar",
            str(apk_tool),
            "-q",
            "build",
            "--use-aapt2",
            self.temp_path,
            "--output",
            self.output_path,
        ]
        cprint("[+] Compiling the smali with apktool.", "green")
        subprocess.check_call(command, timeout=20 * 60)
        cprint("[+] Smali has been compiled.", "green")
        shutil.rmtree(self.temp_path)

    def sign_apk(self):
        uber_signer = (
            pathlib.Path(__file__).parent / "bin" / "uber-apk-signer-1.2.1.jar"
        )
        command = [
            "java",
            "-jar",
            str(uber_signer),
            "--apks",
            self.output_path,
        ]
        cprint("[+] Signing the apk with uber apk signer.", "green")
        subprocess.check_call(command, timeout=20 * 60)
        cprint("[+] Finished successfully.", "green")
        os.remove(self.output_path)
        os.rename(
            f'{self.output_path.removesuffix(".apk")}-aligned-debugSigned.apk',
            self.output_path,
        )
