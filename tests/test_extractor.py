from whatsapp_patcher.extractor import Extractor
from whatsapp_patcher.patcher import Patcher
import pytest
import requests
import re

latest_version_re = re.compile(
    '<a class="downloadLink" href=".*?([0-9]+-[0-9]+-[0-9]+-[0-9]+).*?">'
)
download_link_re = re.compile('href="(/apk/whatsapp-inc/.*?download/download/.*?)"')
click_here_re = re.compile('href="(.*APKMirror/download.php\?id=.*?)"')
headers = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36",
}


@pytest.fixture(scope="session")
def apk_path(tmp_path_factory) -> str:
    tmp = tmp_path_factory.mktemp("tmp")
    apk_path = str(tmp / "WhatsApp.apk")

    def get_latest_version_download_link() -> str:
        versions_html = requests.get(
            "https://www.apkmirror.com/apk/whatsapp-inc/whatsapp/", headers=headers
        ).text
        latest_version = latest_version_re.findall(versions_html)[0]
        url = f"https://www.apkmirror.com/apk/whatsapp-inc/whatsapp/whatsapp-{latest_version}-release/whatsapp-messenger-{latest_version}-android-apk-download/"
        download_page = requests.get(url, headers=headers).text
        return download_link_re.findall(download_page)[0]

    def download_latest_whatsapp(path):
        download_link = get_latest_version_download_link()
        click_here_page = requests.get(
            f"https://www.apkmirror.com{download_link}", headers=headers
        ).text
        click_here_link = click_here_re.findall(click_here_page)[0]
        res = requests.get(
            f"https://www.apkmirror.com{click_here_link}", headers=headers
        )
        with open(path, "wb") as f:
            f.write(res.content)

    download_latest_whatsapp(apk_path)
    return apk_path


@pytest.fixture(scope="session")
def output_path(tmp_path_factory) -> str:
    tmp_path = tmp_path_factory.mktemp("output")
    return str(tmp_path / "output.apk")


@pytest.fixture(scope="session")
def extractor(apk_path: str, output_path: str, tmp_path_factory):
    tmp_path = tmp_path_factory.mktemp("extracted")
    return Extractor(apk_path, output_path, str(tmp_path / "extracted"))


@pytest.fixture()
def patcher(extractor: Extractor) -> Patcher:
    return Patcher(extractor.temp_path)


def test_extract_apk(extractor: Extractor):
    extractor.extract_apk()


def test_patches(patcher: Patcher):
    patcher.patch()


def test_compile_apk(extractor: Extractor):
    extractor.compile_smali()


def test_sign_apk(extractor: Extractor):
    extractor.sign_apk()


pytest.main()
