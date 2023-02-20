from whatsapp_patcher.extractor import Extractor
from whatsapp_patcher.patcher import Patcher
from whatsapp_patcher.utils.downloader import download_latest_whatsapp
import pytest
import requests
import re

latest_version_re = re.compile(
    '<a class="downloadLink" href=".*?([0-9]+-[0-9]+-[0-9]+-[0-9]+).*?">'
)
download_link_re = re.compile('href="(/apk/whatsapp-inc/.*?download/download/.*?)"')
click_here_re = re.compile('href="(.*APKMirror/download.php.id=.*?)"')
headers = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36",
}


@pytest.fixture(scope="session")
def apk_path(tmp_path_factory) -> str:
    tmp = tmp_path_factory.mktemp("tmp")
    apk_path = str(tmp / "WhatsApp.apk")
    download_latest_whatsapp(apk_path)
    return apk_path


@pytest.fixture(scope="session")
def output_path(tmp_path_factory) -> str:
    tmp_path = tmp_path_factory.mktemp("output")
    return str(tmp_path / "PatchedWhatsApp.apk")


@pytest.fixture(scope="session")
def extractor(apk_path: str, output_path: str, tmp_path_factory):
    tmp_path = tmp_path_factory.mktemp("extracted")
    return Extractor(apk_path, output_path, str(tmp_path / "extracted"))


@pytest.fixture()
def patcher(extractor: Extractor) -> Patcher:
    return Patcher(extractor.temp_path, should_enable_ab_tests=True)


def test_extract_apk(extractor: Extractor):
    extractor.extract_apk()


def test_patches(patcher: Patcher):
    patcher.patch()


def test_compile_apk(extractor: Extractor):
    extractor.compile_smali()


def test_sign_apk(extractor: Extractor):
    extractor.sign_apk()


pytest.main()
