from whatsapp_patcher.patches.Patch import Patch
import re


class ViewOncePatch(Patch):
    VIEW_ONCE_METHOD_RE = re.compile(
        r"\w+\(Landroid\/view\/Menu;Landroid\/view\/MenuInflater;\)V(.*?)\.end method",
        re.DOTALL,
    )
    VIEW_ONCE_MODE_REG_RE = re.compile(r"const(?:\/\w+)? (v[0-9])+, 0x3")

    def __init__(self, extracted_path):
        super().__init__(extracted_path)
        self.print_message = "[+] Patching the view once method..."

    def class_filter(self, class_data: str) -> bool:
        if ".class public Lcom/whatsapp/mediaview/MediaViewFragment;" in class_data:
            return True
        return False

    def class_modifier(self, class_data) -> str:
        view_once_method_body = self.VIEW_ONCE_METHOD_RE.findall(class_data)[0]
        register = self.VIEW_ONCE_MODE_REG_RE.findall(view_once_method_body)[0]
        new_view_once_method_body = view_once_method_body.replace(
            f"{register}, 0x3", f"{register}, 0x4"
        )
        return class_data.replace(
            view_once_method_body,
            new_view_once_method_body,
        )
