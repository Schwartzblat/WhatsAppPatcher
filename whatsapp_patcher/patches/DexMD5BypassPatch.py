from whatsapp_patcher.patches.Patch import Patch
import re
from hashlib import md5


class DexMD5BypassPatch(Patch):
    DEX_HASH_BODY = re.compile(
        r":try[^\n]+\s+[^\n]+getPackageCodePath\(\).*?invoke-virtual \{(?P<mac>\w+),\s*(?P<reg>\w+)\},\s*Ljavax\/crypto\/Mac;->update\(\[B\)V",
        re.DOTALL,
    )

    def __init__(self, extracted_path):
        super().__init__(extracted_path)
        self.print_message = "[+] Bypassing dex file md5 hash"

    def get_dex_md5(self) -> bytes:
        with open(self.extracted_path + "/classes.dex", "rb") as f:
            return md5(f.read()).digest()

    def class_filter(self, class_data: str) -> bool:
        if "app/md5/bytes/error" in class_data:
            return True
        return False

    def class_modifier(self, class_data: str) -> str:
        dex_hash = self.get_dex_md5()
        dex_hash_body = list(self.DEX_HASH_BODY.finditer(class_data))[0]
        array_register = dex_hash_body.groupdict().get("reg")
        mac_register = dex_hash_body.groupdict().get("mac")
        array_size = len(dex_hash)
        dex_hash_new_body = f"""
    const {array_register}, {hex(array_size)}

    new-array {array_register}, {array_register}, [B
    
    fill-array-data {array_register}, :my_array_data
    
    goto :after_array_data
    
    :my_array_data
    .array-data 1"""
        for byte in dex_hash:
            dex_hash_new_body += f"\n\t{hex(byte)}"
        dex_hash_new_body += f"""
    .end array-data
    :after_array_data
    invoke-virtual {{{mac_register}, {array_register}}}, Ljavax/crypto/Mac;->update([B)V
        """
        return class_data.replace(dex_hash_body.group(0), dex_hash_new_body)
