from whatsapp_patcher.patches.Patch import Patch
import re


class SignatureVerifierPatch(Patch):
    SIGNATURE_START = 120
    SIGNATURE_END = -444
    SIGN_VERIFICATION_RE = re.compile(
        r"\.method public static \w+\(Landroid\/content\/Context;Ljava/lang/String;\)\[Landroid\/content\/pm\/Signature;\s*\.\w+ \w+\s*(.*?)\s*.end method",
        re.DOTALL,
    )
    SIGN_VERIFICATION_REPLACE = """
        const-string/jumbo v0, "{{ORIGINAL_SIGNATURE}}"

        new-instance v4, Landroid/content/pm/Signature;

        invoke-direct {v4, v0}, Landroid/content/pm/Signature;-><init>(Ljava/lang/String;)V

        const/4 v0, 0x1

        const/4 v2, 0x0

        const/4 v1, 0x1

        new-array v0, v0, [Landroid/content/pm/Signature;

        aput-object v4, v0, v2

        return-object v0 
        """

    def __init__(self, extracted_path):
        super().__init__(extracted_path)
        self.print_message = "[+] Bypassing package manager signature..."

    def class_filter(self, class_data: str) -> bool:
        if "PackageManagerUtils/setActivityRegisteredState/error:" in class_data:
            return True
        return False

    def class_modifier(self, class_data: str) -> str:
        original_signature = self.get_original_signature()
        sign_verification_method_body = list(
            self.SIGN_VERIFICATION_RE.finditer(class_data)
        )[0]
        return class_data.replace(
            sign_verification_method_body.group(1),
            self.SIGN_VERIFICATION_REPLACE.replace(
                "{{ORIGINAL_SIGNATURE}}", original_signature
            ),
        )

    def get_original_signature(self) -> str:
        with open(self.extracted_path + "/original/META-INF/IMPORTED.DSA", "rb") as f:
            bytes_signature = f.read()
        signature = ""
        for byte in bytes_signature:
            signature_byte = hex(byte).split("x")[-1]
            if len(signature_byte) == 1:
                signature_byte = "0" + signature_byte
            signature += signature_byte
        return signature[self.SIGNATURE_START : self.SIGNATURE_END]
