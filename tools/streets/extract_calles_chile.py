import argparse

import osmium


class ChileStreetNames(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.names = set()

    def way(self, way):
        if "highway" in way.tags and "name" in way.tags:
            name = way.tags["name"].strip()
            if name:
                self.names.add(name)


def main():
    parser = argparse.ArgumentParser(description="Extrae nombres de calles desde un PBF de OpenStreetMap")
    parser.add_argument("pbf", help="Archivo chile-latest.osm.pbf")
    parser.add_argument("output", help="Archivo de salida, por ejemplo data/calles_chile.txt")
    args = parser.parse_args()

    handler = ChileStreetNames()
    handler.apply_file(args.pbf, locations=False)
    with open(args.output, "w", encoding="utf-8") as output:
        output.write("\n".join(sorted(handler.names)))
        output.write("\n")
    print(f"{len(handler.names)} calles")


if __name__ == "__main__":
    main()