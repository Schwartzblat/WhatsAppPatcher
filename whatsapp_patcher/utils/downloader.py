import requests
import re
from termcolor import cprint


latest_version_re = re.compile(
    '<a class="downloadLink" href=".*?([0-9]+-[0-9]+-[0-9]+-[0-9]+).*?">'
)
download_link_re = re.compile('href="(/apk/whatsapp-inc/.*?download/download/.*?)"')
click_here_re = re.compile('href="(.*APKMirror/download.php.id=.*?)"')
headers = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36",
}
apk_mirror_url = "https://www.apkmirror.com"


def get_latest_version_download_link() -> str:
    versions_html = requests.get(
        f"{apk_mirror_url}/apk/whatsapp-inc/whatsapp/", headers=headers
    ).text
    latest_version = latest_version_re.findall(versions_html)[0]
    url = f"{apk_mirror_url}/apk/whatsapp-inc/whatsapp/whatsapp-{latest_version}-release/whatsapp-messenger-{latest_version}-android-apk-download/"
    download_page = requests.get(url, headers=headers).text
    download_link = download_link_re.findall(download_page)[0]
    click_here_page = requests.get(
        f"{apk_mirror_url}{download_link}", headers=headers
    ).text
    return click_here_re.findall(click_here_page)[0]


def download_latest_whatsapp(path: str):
    cprint("[+] Downloading latest WhatsApp version from apkmirror", "green")
    download_link = get_latest_version_download_link()
    res = requests.get(f"{apk_mirror_url}{download_link}", headers=headers)
    with open(path, "wb") as f:
        f.write(res.content)
