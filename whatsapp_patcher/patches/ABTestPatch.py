from whatsapp_patcher.patches.Patch import Patch
import re


class ABTestsPatch(Patch):
    BOOLEAN_TEST_METHOD_BODY_REGEX_SMALI = re.compile(
        "\.method public final \w+\(LX\/\w+;I\)Z(.*?)\.end method", re.DOTALL
    )
    BAD_TESTS = [
        "0x936",
        "0x33F",
        "0x93",
    ]

    def __init__(self, extracted_path):
        super().__init__(extracted_path)
        self.print_message = (
            "[+] Patching AB tests class to enable all hidden features..."
        )

    def replace_return_values_smali(self):
        new_method_body = "\n.registers 3"
        for test in self.BAD_TESTS:
            new_method_body += f"""
    const v1, {test}                               
    if-eq p2, v1, :cond_ab_tests"""

        new_method_body += """
    const v0, 1
    :cond_ab_tests
    return v0
    """
        return new_method_body

    def class_filter(self, class_data: str) -> bool:
        if ', "Unknown BooleanField: "' in class_data:
            return True
        return False

    def class_modifier(self, class_data) -> str:
        function_body = self.BOOLEAN_TEST_METHOD_BODY_REGEX_SMALI.findall(class_data)[0]
        new_function_body = self.replace_return_values_smali()
        return class_data.replace(function_body, new_function_body)
