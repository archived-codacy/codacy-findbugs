"""Generate FindBugs's patterns.json and description.json by parsing the online descriptions."""
import json
import textwrap

import bs4
import requests

DESCRIPTION_URL = "http://findbugs.sourceforge.net/bugDescriptions.html"

PATTERNS = {
  "name": "FindBugs",
  "patterns": []
}


def retrieve_descriptions():
    content = requests.get(DESCRIPTION_URL)
    soup = bs4.BeautifulSoup(content.text)
    for header in soup.find_all('h3'):
        desc = header.find('a')
        name = desc.attrs['name']
        title = desc.text.split(":", 1)[1]
        title = title.replace("({})".format(name), "").strip()
        description = header.findNextSibling("p").text.strip()
        # The description looks really weird and requires manual modifications.
        yield name, title


def generate_files():
    descriptions = []

    for name, title in retrieve_descriptions():
        PATTERNS['patterns'].append({
            'patternId': name,
            'level': 'Error',
            'category': 'Security'
        })
        descriptions.append({
            "patternId": name,
            "title": title,
            "description": "",
            "timeToFix": 30
        })

    with open('patterns.json', 'w') as stream:
        json.dump(PATTERNS, stream, indent=2)
    with open('description.json', 'w') as stream:
        json.dump(descriptions, stream, indent=2)


generate_files()