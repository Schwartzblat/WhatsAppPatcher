from whatsapp_patcher.extractor import Extractor
from whatsapp_patcher.patcher import Patcher
from whatsapp_patcher.utils.downloader import download_latest_whatsapp
import pytest
import requests
import re


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
