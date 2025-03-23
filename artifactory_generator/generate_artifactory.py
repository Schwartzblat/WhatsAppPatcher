"""
This module supposed to be edited by the user to generate the artifactory for their project.
"""
import json


def generate_artifactory(args):
    with open(args.artifactory, 'w') as file:
        json.dump({'SOME_CONST_KEY': 'VALUE'}, file)
