import json
import pathlib
from artifactory_generator.generate_artifactory import generate_artifactory


def prepare_artifactory(args):
    artifactory_path = pathlib.Path(args.artifactory)
    if artifactory_path.exists():
        try:
            with open(artifactory_path, 'r') as file:
                json.load(file)
        except json.decoder.JSONDecodeError:
            pass
        else:
            return
    generate_artifactory(args)
