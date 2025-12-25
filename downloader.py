from typing import Optional
import requests
import re

WHATSAPP_VERSIONS_RE = re.compile(
    '<a class="downloadLink" href=".*?([0-9]+-[0-9]+-[0-9]+-[0-9]+).*?">'
)
download_link_re = re.compile('href="(/apk/whatsapp-inc/.*?download/download/.*?)"')
click_here_re = re.compile('href="(.*APKMirror/download.php.id=.*?)"')

headers = {
    "User-Agent": "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.7103.126 Mobile Safari/537.36"}  # noqa: E501
apk_mirror_url = "https://www.apkmirror.com"
IS_BUNDLE_STRING = 'APK bundle'


def get_latest_version_download_link() -> Optional[str]:
    versions_html = requests.get(
        f"{apk_mirror_url}/apk/whatsapp-inc/whatsapp/", headers=headers
    ).text
    versions = WHATSAPP_VERSIONS_RE.findall(versions_html)
    for version in versions:
        url = f"{apk_mirror_url}/apk/whatsapp-inc/whatsapp/whatsapp-{version}-release/whatsapp-messenger-{version}-android-apk-download/"  # noqa: E501
        download_page = requests.get(url, headers=headers).text
        if IS_BUNDLE_STRING in download_page:
            print(f"[-] {version} is a bundle, skipping")
            continue
        download_link = download_link_re.findall(download_page)[0]
        click_here_page = requests.get(
            f"{apk_mirror_url}{download_link}", headers=headers
        ).text
        return click_here_re.findall(click_here_page)[0]
    return None


def download_latest_whatsapp(path: str):
    print("[+] Downloading latest WhatsApp version from apkmirror")
    download_link = get_latest_version_download_link().replace("&amp;", "&")
    if not download_link:
        print("[-] Failed to get a valid download link")
        return None
    res = requests.get(f"{apk_mirror_url}{download_link}", headers=headers)
    with open(path, "wb") as f:
        f.write(res.content)
    return path
