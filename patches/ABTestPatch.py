from patches.Patch import Patch
import re

class ABTestsPatch(Patch):
    BOOLEAN_TEST_METHOD_BODY_REGEX_SMALI = re.compile(
        "\.method public final \w+\(LX\/\w+;I\)Z.*?end method", re.DOTALL
    )
    RETURN_RE_SMALI = re.compile("[ ]*return v[0-9]")
    def __init__(self, extracted_path):
        super().__init__(extracted_path)
        self.print_message = '[+] Patching AB tests class to enable all hidden features...'

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
    const {temp_register}, 0x33F
    if-eq p2, {temp_register}, :cond_{arr[counter]}
    const {register_name}, 1
    :cond_{arr[counter]}
    {match.group().strip()}
                    """,
            )
            counter += 1
        return new_method_body
    def class_filter(self, class_data: str) -> bool:
        if ', "Unknown BooleanField: "' in class_data:
            return True
        return False

    def class_modifier(self, class_data) -> str:
        function_body = self.BOOLEAN_TEST_METHOD_BODY_REGEX_SMALI.findall(class_data)[0]
        new_function_body = self.replace_return_values_smali(function_body)
        return class_data.replace(function_body, new_function_body)