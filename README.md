# CODES

Sistema de gestión y clasificación de llamadas de emergencia con ASR local, autenticación JWT, geolocalización y panel operativo web.

CODES es una aplicación Spring Boot para registrar llamadas, detectar prioridad, extraer dirección, cifrar audios y operar con una interfaz de despacho centralizada.

## ¿Qué incluye?

- Frontend web con mapa, dashboard y flujo de llamadas
- API REST con Spring Boot y Spring Security
- Autenticación por JWT + BCrypt
- Clasificación heurística de emergencias por texto
- Geocodificación y coordenadas para incidentes
- ASR local con sherpa-onnx para transcripción en tiempo real o por archivo
- Cifrado AES-256-GCM para audios almacenados
- Base de datos H2 local para entorno de desarrollo

## Stack principal

- Java 21
- Spring Boot 3
- Spring Security
- Spring Data JPA
- H2 Database
- Leaflet + Esri World Imagery
- sherpa-onnx ASR

## Requisitos

- Java 21
- Maven
- Windows recomendado para la parte de ASR local

## Inicio rápido

```powershell
$env:CODES_JWT_SECRET="genera-un-secreto-largo-y-aleatorio"
$env:CODES_ENCRYPT_KEY="genera-una-clave-base64-de-32-bytes"
$env:CODES_TURNSTILE_SITE_KEY="clave-publica-de-Cloudflare-Turnstile"
$env:CODES_TURNSTILE_SECRET_KEY="clave-secreta-de-Cloudflare-Turnstile"
$env:CODES_ADMIN_USER="admin"
$env:CODES_ADMIN_PASSWORD="una-password-segura"

mvn spring-boot:run
```

Turnstile es obligatorio para iniciar sesión y registrarse. Para producción crea
un widget en Cloudflare Turnstile para el dominio que vas a usar y configura
ambas variables antes de iniciar CODES. La `SITE_KEY` puede ser pública; la
`SECRET_KEY` debe permanecer solo en el entorno del servidor y nunca subirse al
repositorio.

Si ejecutas `INICIAR_CODES.bat` sin claves configuradas, **no se pregunta nada**:
el script usa las claves de prueba de Cloudflare (el widget aparece y funciona
normal, pero siempre aprueba) y lo avisa en amarillo al arrancar. Para usar
claves reales con el `.bat`, agrégalas a `data\.codes-secrets.ps1`:

```powershell
$env:CODES_TURNSTILE_SITE_KEY = 'tu-site-key'
$env:CODES_TURNSTILE_SECRET_KEY = 'tu-secret-key'
```

El widget necesita internet (carga `challenges.cloudflare.com`).

Luego abre:

- http://localhost:8000

## Seguridad de red

Por defecto, CODES escucha solo en `127.0.0.1:8000`, por lo que la API no queda
publicada en la red. Para operar desde otros equipos de una red interna, define
`CODES_SERVER_ADDRESS` con la IP privada concreta del equipo servidor y permite
solo ese puerto en el firewall de Windows; no lo publiques directamente en
Internet. El puerto se puede cambiar con `CODES_SERVER_PORT`.

El ASR local usa `localhost:6006` y debe mantenerse accesible solo desde el
equipo que ejecuta la interfaz. No abras ese puerto en el router ni en el
firewall para Internet.

## ASR local

La transcripción se realiza con `sherpa-onnx` y el flujo local se prepara con:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\tools\asr\install_windows.ps1
.\tools\asr\start_asr_windows.ps1
```

El directorio `tools/asr` contiene el script de instalación y arranque del modelo ASR.

## Corrección de calles chilenas

El ASR se mantiene sin cambios. Después de transcribir, CODES corrige nombres de calles
con coincidencia fonética y difusa contra un diccionario de calles, y luego usa ese nombre
corregido para geocodificar (buscar coordenadas) y mostrarlo en los paneles del operador.
El repositorio incluye `data/calles_chile.txt` con un listado base de las avenidas y calles
más conocidas de Santiago, para que la corrección funcione desde el primer arranque. Se
recomienda ampliarlo con el listado completo de OpenStreetMap de la región que uses.

**Opción rápida (recomendada):** usa la Overpass API, no requiere descargar el `.pbf`
completo de Chile ni instalar `osmium`:

```powershell
python -m pip install requests
python tools/streets/fetch_calles_overpass.py data/calles_chile.txt
```

Por defecto descarga la Región Metropolitana. Para otra región usa `--region "Nombre"`,
o `--pais` para todo Chile (tarda más).

**Opción con el PBF completo de Chile** (más pesado, pero sirve si necesitas datos que
Overpass no tenga o quieres trabajar sin conexión después de la descarga inicial):

```powershell
python -m pip install osmium
Invoke-WebRequest https://download.geofabrik.de/south-america/chile-latest.osm.pbf -OutFile chile-latest.osm.pbf
python tools/streets/extract_calles_chile.py chile-latest.osm.pbf data/calles_chile.txt
```

El archivo PBF puede borrarse después de la extracción. La aplicación lo carga desde
`app.calles-diccionario` al iniciar (revisa el log al arrancar: indica cuántos nombres
cargó). Si el archivo no existe o queda vacío, el proyecto conserva la transcripción
original sin romper el flujo, pero la corrección de calles queda desactivada.

**Geocodificación:** además de corregir el nombre, `GeocoderService` ahora hace primero
una búsqueda **estructurada** en Nominatim (le indica explícitamente cuál es la calle) y
solo acepta un resultado si su puntaje de similitud contra lo transcrito supera un umbral
mínimo; antes tomaba a ciegas el primer resultado de la búsqueda en texto libre, lo que
podía anclar el caso en un lugar equivocado sin avisar. También respeta el límite de 1
solicitud por segundo de Nominatim y registra en el log (`logging.level.root`) cuando una
consulta no encuentra nada o falla, para poder diagnosticar casos "a medias".

## Estructura relevante

```text
CODES/
├── src/main/java/cl/codes
├── src/main/resources/static
├── src/main/resources/application.properties
├── tools/asr/
├── data/
├── pom.xml
├── README.md
├── INICIAR_CODES.bat
└── .gitignore
```

## Nota

Este repositorio está orientado al proyecto CODES actual y no incluye versiones antiguas o repos legacy no mantenidas.

## Licencia

Proyecto en desarrollo para uso interno / académico / operativo, según alcance del equipo responsable.

