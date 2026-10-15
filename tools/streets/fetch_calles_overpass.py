"""
Genera data/calles_chile.txt consultando la Overpass API.

El archivo existente se conserva si Overpass falla. Las descargas grandes
(como --pais) deben hacerse de forma ocasional, no en cada arranque.
"""

import argparse
import sys
import time
from pathlib import Path

try:
    import requests
except ImportError:
    print("Falta la librería 'requests'. Instálala con: pip install requests")
    sys.exit(1)

OVERPASS_ENDPOINTS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
]
USER_AGENT = "CODES-Emergencias/1.0 (herramienta de calles; contacto local)"
MAX_ATTEMPTS_PER_ENDPOINT = 2


def construir_consulta(area_nombre: str | None) -> str:
    if area_nombre is None:
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


def _esperar_429(resp, intento: int) -> None:
    valor = resp.headers.get("Retry-After", "")
    try:
        espera = max(2, min(int(valor), 120))
    except (ValueError, TypeError):
        espera = min(10 * intento, 30)
    print(f"  Overpass esta limitando solicitudes (429). Esperando {espera}s antes de reintentar...")
    time.sleep(espera)


def descargar(area_nombre: str | None) -> set[str]:
    consulta = construir_consulta(area_nombre)
    ultimo_error = None
    headers = {
        "User-Agent": USER_AGENT,
        "Accept": "application/json",
        "Accept-Encoding": "gzip, deflate",
    }

    for endpoint in OVERPASS_ENDPOINTS:
        for intento in range(1, MAX_ATTEMPTS_PER_ENDPOINT + 1):
            try:
                print(f"Consultando {endpoint} (intento {intento}/{MAX_ATTEMPTS_PER_ENDPOINT}) ...")
                resp = requests.post(
                    endpoint,
                    data={"data": consulta},
                    headers=headers,
                    timeout=240,
                )

                if resp.status_code == 429:
                    ultimo_error = RuntimeError("HTTP 429 Too Many Requests")
                    if intento < MAX_ATTEMPTS_PER_ENDPOINT:
                        _esperar_429(resp, intento)
                        continue
                    print("  Ese endpoint sigue limitando solicitudes; probando el siguiente...")
                    break

                if resp.status_code == 406:
                    ultimo_error = RuntimeError("HTTP 406 Not Acceptable")
                    print("  El endpoint rechazo la solicitud (406); probando el siguiente...")
                    break

                resp.raise_for_status()
                datos = resp.json()
                nombres = set()
                for elemento in datos.get("elements", []):
                    nombre = elemento.get("tags", {}).get("name", "").strip()
                    if nombre:
                        nombres.add(nombre)
                if nombres:
                    return nombres
                raise RuntimeError("Overpass respondió correctamente pero no devolvió nombres de calles")
            except (requests.RequestException, ValueError, RuntimeError) as e:
                ultimo_error = e
                print(f"  Falló ese intento ({e}).")
                if intento < MAX_ATTEMPTS_PER_ENDPOINT:
                    time.sleep(3)
                    continue
                print("  Pasando al siguiente endpoint...")
                break

        time.sleep(2)

    raise RuntimeError(f"No se pudo consultar Overpass en ningún endpoint: {ultimo_error}")


def main():
    parser = argparse.ArgumentParser(description="Genera el diccionario de calles chilenas vía Overpass API")
    parser.add_argument("output", help="Archivo de salida, por ejemplo data/calles_chile.txt")
    parser.add_argument("--region", default="Región Metropolitana de Santiago",
                        help="Nombre OSM de la región")
    parser.add_argument("--pais", action="store_true", help="Descarga las calles de todo Chile (más lento)")
    parser.add_argument("--reemplazar", action="store_true",
                        help="Sobrescribe el archivo de salida en vez de fusionarlo")
    args = parser.parse_args()

    area = None if args.pais else args.region
    nombres = descargar(area)

    if not args.reemplazar:
        try:
            with open(args.output, "r", encoding="utf-8") as f:
                existentes = {linea.strip() for linea in f if linea.strip()}
            antes = len(nombres)
            nombres |= existentes
            print(f"Fusionando con {len(existentes)} calles ya presentes en {args.output} "
                  f"({len(nombres) - antes} nuevas desde Overpass).")
        except FileNotFoundError:
            pass

    # Escritura atómica: una consulta fallida nunca deja el diccionario vacío/corrupto.
    destino = Path(args.output)
    temporal = destino.with_suffix(destino.suffix + ".tmp")
    temporal.write_text("\n".join(sorted(nombres, key=str.lower)) + "\n", encoding="utf-8")
    temporal.replace(destino)

    print(f"{len(nombres)} calles guardadas en {args.output}")


if __name__ == "__main__":
    main()
