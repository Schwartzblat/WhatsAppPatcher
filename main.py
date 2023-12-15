import argparse
import os
from termcolor import cprint
from whatsapp_patcher.extractor import Extractor
from whatsapp_patcher.utils.downloader import download_latest_whatsapp
from whatsapp_patcher.patcher import Patcher
from timeit import default_timer


def main():
    start = default_timer()
    parser = argparse.ArgumentParser(description="Bump Version")
    parser.add_argument("-path", "-p", dest="path", type=str, default="latest")
    parser.add_argument(
        "-output", "-o", dest="output", type=str, default="PatchedWhatsApp.apk"
    )
    parser.add_argument(
        "--temp-path", dest="temp_path", type=str, default="./extracted"
    )
    parser.add_argument("--ab-tests", action="store_true")
    args = parser.parse_args()
    path = args.path
    if path == "latest":
        download_latest_whatsapp("WhatsApp.apk")
        path = "WhatsApp.apk"
    if not os.path.exists(path) or not os.access(path, os.R_OK):
        cprint("[+] File doesn't exists or required reading permissions", color="red")
        exit(-1)
    if not path.endswith(".apk") or not args.output.endswith(".apk"):
        cprint(
            "[+] Input path and output path supposed to be a path to apk.", color="red"
        )
        exit(-1)
    extractor = Extractor(path, args.output, args.temp_path)
    extractor.extract_apk()
    patcher = Patcher(extractor.temp_path, args.ab_tests)
    patcher.patch()
    extractor.compile_smali()
    extractor.sign_apk()
    print(f"It took {default_timer()-start} seconds to complete the run.")


if __name__ == "__main__":
    main()
