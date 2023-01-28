from termcolor import cprint
import os
import re
import glob
from hashlib import md5
from typing import Callable

class Patcher:

    BOOLEAN_TEST_METHOD_BODY_REGEX_SMALI = re.compile(
        "\.method public final (?:\w+)\(LX\/0uH;I\)Z.*?end method", re.DOTALL
    )
    RETURN_RE_SMALI = re.compile("[ ]*return v[0-9]")
    SIGN_VERIFICATION_RE = re.compile(
        r'\.method public static \w+\(Landroid\/content\/Context;\)\[Landroid\/content\/pm\/Signature;\s*\.locals \w+\s*(.*?)\s*.end method',
        re.DOTALL,
    )
    SIGN_VERIFICATION_REPLACE = """.line 774171
    const-string/jumbo v0, "{{ORIGINAL_SIGNATURE}}"

    .line 774172
    new-instance v4, Landroid/content/pm/Signature;

    invoke-direct {v4, v0}, Landroid/content/pm/Signature;-><init>(Ljava/lang/String;)V
    
    const/4 v0, 0x1

    const/4 v2, 0x0

    const/4 v1, 0x1

    .line 774175
    new-array v0, v0, [Landroid/content/pm/Signature;

    aput-object v4, v0, v2

    return-object v0 
    """

    DEX_HASH_FUNCTION_RE = re.compile('\.method public static \w+\(Landroid\/content\/Context;\)\[B\s*(.*?)\.end method', re.DOTALL)
    def __init__(self, extracted_path, apk_path):
        self.extracted_path = extracted_path
        self.apk_path = apk_path

    def patch(self):
        self.bypass_signature_verifier()
        self.patch_ab_tests()

    def patch_ab_tests(self):
        self.patch_class(self.is_abtests_class, self.get_new_ab_test_class)

    def get_new_ab_test_class(self, class_data) -> str:
        function_body = self.BOOLEAN_TEST_METHOD_BODY_REGEX_SMALI.findall(class_data)[0]
        new_function_body = self.replace_return_values_smali(function_body)
        return class_data.replace(function_body, new_function_body)

    def bypass_signature_verifier(self):
        cprint("[+] Bypassing signature verifier...", "green")
        cprint("[+] Bypassing package manager signature...", "green")
        self.patch_class(self.get_sign_verification_class, self.get_new_sign_verification_class)
        cprint("[+] Package manager signature has been modified", "green")
        cprint("[+] Bypassing dex file md5 hash", "green")
        self.patch_class(self.get_dex_hash_class, self.get_new_dex_hash_class_data)
        cprint("[+] Dex file md5 hash has been modified", "green")
        cprint("[+] Signature verifier class has been bypasses.", "green")

    def get_original_signature(self) -> str:
        with open(self.extracted_path+'/original/META-INF/WHATSAPP.DSA', 'rb') as f:
            bytes_signature = f.read()
        signature = ''
        for byte in bytes_signature:
            signature_byte = hex(byte).split('x')[-1]
            if len(signature_byte) == 1:
                signature_byte = '0'+signature_byte
            signature += signature_byte
        return signature[112:-432]

    def get_dex_md5(self) -> bytes:
        with open('classes.dex', 'rb') as f:
            return md5(f.read()).digest()

    def get_new_dex_hash_class_data(self, class_data: str) -> str:
        dex_hash = self.get_dex_md5()
        dex_hash_function = list(self.DEX_HASH_FUNCTION_RE.finditer(class_data))[0]
        array_size = len(dex_hash)
        new_dex_hash_function = f""".registers 7
    
    const v2, {hex(array_size)}
    
    new-array v0, v2, [B
    """
        i = 0
        for byte in dex_hash:
            new_dex_hash_function += f"""
    const v5, {hex(byte)}
    
    const v1, {hex(i)}
    
    aput-byte v5, v0, v1
            """
            i += 1
        new_dex_hash_function += """
    
    return-object v0
    """
        return class_data.replace(
            dex_hash_function.group(1),
            new_dex_hash_function
        )


    def get_dex_hash_class(self, data: str) -> bool:
        if "app/md5/bytes/error" in data:
            return True
        return False

    def get_sign_verification_class(self, data: str) -> bool:
        if "PackageManagerUtils/setActivityRegisteredState/error:" in data:
            return True
        return False

    def get_new_sign_verification_class(self, class_data: str) -> str:
        original_signature = self.get_original_signature()
        sign_verification_method_body = list(self.SIGN_VERIFICATION_RE.finditer(class_data))[0]
        return class_data.replace(
            sign_verification_method_body.group(1),
            self.SIGN_VERIFICATION_REPLACE.replace('{{ORIGINAL_SIGNATURE}}', original_signature)
        )

    def is_abtests_class(self, data: str):
        if ', "Unknown BooleanField: "' in data:
            return True
        return False

    def replace_return_values_smali(self, method_body):
        new_method_body = method_body
        arr = [6, 7]
        counter = 0
        for match in self.RETURN_RE_SMALI.finditer(method_body):
            register_name = match.group().strip().split(" ")[1]
            temp_register = "v2"
            if register_name == "v2":
                temp_register = "v0"
            new_method_body = new_method_body.replace(
                match.group(),
                f"""
    const {temp_register}, 0x936                               
    if-eq p2, {temp_register}, :cond_{arr[counter]}
    const {register_name}, 1
    :cond_{arr[counter]}
    {match.group().strip()}
                    """,
            )
            counter += 1
        return new_method_body

    def patch_class(self, class_filter: Callable[[str], bool], class_modifier: Callable[[str], str]) -> bool:
        class_path, class_data = self.find_class(class_filter)
        if class_path is None:
            return False
        new_class_data = class_modifier(class_data)
        with open(class_path, 'w') as f:
            f.write(new_class_data)
        return True

    def find_class(self, class_filter: Callable[[str], bool]) -> tuple[str, str]:
        for filename in glob.iglob(
            os.path.join(self.extracted_path, "**", "*.smali"), recursive=True
        ):
            with open(filename, "r", encoding="utf8") as f:
                data = f.read()
                if class_filter(data):
                   return filename, data
        return None, None