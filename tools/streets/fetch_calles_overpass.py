"""
Genera data/calles_chile.txt consultando la Overpass API directamente,
sin necesitar descargar el .osm.pbf de todo Chile ni instalar osmium.

Uso normal (Región Metropolitana, la que usa CENCO):
    pip install requests
    python tools/streets/fetch_calles_overpass.py data/calles_chile.txt

Para otra región (ver nombres oficiales en Wikipedia "Regiones de Chile"):
    python tools/streets/fetch_calles_overpass.py data/calles_chile.txt --region "Región de Valparaíso"

Para todo el país (tarda varios minutos y el archivo resultante es grande,
pero sigue siendo mucho más simple que trabajar con el .pbf):
    python tools/streets/fetch_calles_overpass.py data/calles_chile.txt --pais

Si este script no se ejecuta (por ejemplo, sin internet en este momento),
la app sigue funcionando con el diccionario base que ya viene incluido en
data/calles_chile.txt; solo no corregirá calles fuera de esa lista.
"""

import argparse
import sys
import time

try:
    import requests
except ImportError:
    print("Falta la librería 'requests'. Instálala con: pip install requests")
    sys.exit(1)

OVERPASS_ENDPOINTS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
]


def construir_consulta(area_nombre: str | None) -> str:
    if area_nombre is None:
        # Todo Chile por código ISO, sin depender del nombre exacto del área.
        area = 'area["ISO3166-1"="CL"][admin_level=2];'
    else:
        area = f'area["name"="{area_nombre}"]["admin_level"~"^(4|8)$"];'
    return f"""
    [out:json][timeout:180];
    {area}
    (
      way["highway"]["name"](area);
    );
    out tags;
    """


def descargar(area_nombre: str | None) -> set[str]:
    consulta = construir_consulta(area_nombre)
    ultimo_error = None
    for endpoint in OVERPASS_ENDPOINTS:
        try:
            print(f"Consultando {endpoint} ...")
            resp = requests.post(endpoint, data={"data": consulta}, timeout=200)
            resp.raise_for_status()
            datos = resp.json()
            nombres = set()
            for elemento in datos.get("elements", []):
                nombre = elemento.get("tags", {}).get("name", "").strip()
                if nombre:
                    nombres.add(nombre)
            if nombres:
                return nombres
        except Exception as e:  # noqa: BLE001
            ultimo_error = e
            print(f"  Falló ese endpoint ({e}); probando el siguiente...")
            time.sleep(2)
    raise RuntimeError(f"No se pudo consultar Overpass en ningún endpoint: {ultimo_error}")


def main():
    parser = argparse.ArgumentParser(description="Genera el diccionario de calles chilenas vía Overpass API")
    parser.add_argument("output", help="Archivo de salida, por ejemplo data/calles_chile.txt")
    parser.add_argument("--region", default="Región Metropolitana de Santiago",
                         help='Nombre OSM de la región (por defecto la Región Metropolitana)')
    parser.add_argument("--pais", action="store_true", help="Descarga las calles de todo Chile (más lento)")
    args = parser.parse_args()

    area = None if args.pais else args.region
    nombres = descargar(area)

    with open(args.output, "w", encoding="utf-8") as f:
        f.write("\n".join(sorted(nombres)))
        f.write("\n")

    print(f"{len(nombres)} calles guardadas en {args.output}")


if __name__ == "__main__":
    main()
