import gzip

import plac  # pip3 install plac


# Is going to return this data as folder per relation
# You need first to download Wikidata truthy dump: https://dumps.wikimedia.org/other/wikibase/wikidatawiki/latest-truthy.nt.gz and pass its path as first argument of this script

def parse_triples(file_name):
    with gzip.open(file_name, 'rt') as file:
        for line in file:
            parts = [e.strip(' \r<>')
                         .replace('http://www.wikidata.org/prop/direct/', '')
                         .replace('http://www.wikidata.org/entity/', '') for e in line.strip(' \t\r\n.').split(' ', 2)]
            if len(parts) == 3:
                yield parts
            else:
                print(parts)


predicates_map = {
    'P17': 'hasCountry',
    'P19': 'hasBirthPlace',
    'P20': 'hasDeathPlace',
    'P21': 'hasGender',
    'P22': 'hasFather',
    'P25': 'hasMother',
    'P26': 'hasSpouse',
    'P27': 'hasNationality',
    'P40': 'hasChild',
    'P131': 'isLocatedIn',
    'P150': 'containsLocation',
    'P3373': 'hasSibling',
    'P3448': 'hasStepParent'
}


def main(input_file):
    files = {prop: open('people/' + prop, 'wt') for prop in predicates_map.values()}
    for (s, p, o) in parse_triples(input_file):
        if p in predicates_map:
            files[predicates_map[p]].write('{}\t{}\n'.format(s, o))


if __name__ == '__main__':
    plac.call(main)
